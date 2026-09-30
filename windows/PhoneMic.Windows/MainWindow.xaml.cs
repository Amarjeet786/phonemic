using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using NAudio.Wave;

namespace PhoneMic.Windows;

/// <summary>
/// Phase 1 receiver: UDP PCM16 mono 16 kHz -> selected audio output device.
/// Packet = "PMIC"(4) + pin(int32) + seq(int32) + PCM. Not encrypted yet.
/// </summary>
public partial class MainWindow : Window
{
    private const int Port = 50505;
    private const int HeaderSize = 12;

    private const int DiscoveryPort = 50506;
    private UdpClient? _discovery;
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private BufferedWaveProvider? _buffer;
    private WaveOutEvent? _output;
    private readonly string _pin;
    private readonly int _pinValue;
    private volatile float _gain = 1.0f;
    private volatile bool _muted;
    private long _lastPacketTicks;
    private string _sender = "";
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };

    public MainWindow()
    {
        InitializeComponent();
        _pinValue = Random.Shared.Next(100000, 999999);
        _pin = _pinValue.ToString();
        PinText.Text = "PIN: " + _pin;

        var ips = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                        n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => a.Address.ToString());
        IpText.Text = "IP: " + string.Join("  /  ", ips);

        var devices = new List<string>();
        for (int i = 0; i < WaveOut.DeviceCount; i++) devices.Add(WaveOut.GetCapabilities(i).ProductName);
        DeviceCombo.ItemsSource = devices;
        int cable = devices.FindIndex(d => d.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase));
        DeviceCombo.SelectedIndex = cable >= 0 ? cable : (devices.Count > 0 ? 0 : -1);

        StartDiscoveryResponder();

        _timer.Tick += (_, _) => RefreshStatus();
        _timer.Start();
        Closed += (_, _) => { Stop(); _discovery?.Close(); };
    }

    // Lets the phone find this PC over Wi-Fi. Audio still needs the PIN.
    private void StartDiscoveryResponder()
    {
        try
        {
            var u = new UdpClient { ExclusiveAddressUse = false };
            u.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            u.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
            _discovery = u;
            _ = Task.Run(async () =>
            {
                try
                {
                    while (true)
                    {
                        var r = await u.ReceiveAsync();
                        if (Encoding.UTF8.GetString(r.Buffer) != "PMIC_DISCOVER") continue;
                        var reply = Encoding.UTF8.GetBytes($"PMIC_HERE|{Environment.MachineName}|{Port}");
                        await u.SendAsync(reply, reply.Length, r.RemoteEndPoint);
                    }
                }
                catch (Exception) { }
            });
        }
        catch (SocketException) { }
    }

    private void RefreshStatus()
    {
        if (_udp == null) return;
        var age = DateTime.UtcNow - new DateTime(Interlocked.Read(ref _lastPacketTicks), DateTimeKind.Utc);
        if (age < TimeSpan.FromSeconds(2))
        {
            StatusText.Text = "● Connected: " + _sender;
            StatusText.Foreground = Brushes.Green;
        }
        else
        {
            StatusText.Text = "● Waiting for phone...";
            StatusText.Foreground = Brushes.Gray;
            LevelBar.Value = 0;
        }
    }

    private void StartButton_Click(object sender, RoutedEventArgs e)
    {
        if (_udp != null) { Stop(); return; }
        try
        {
            _buffer = new BufferedWaveProvider(new WaveFormat(16000, 16, 1))
            {
                BufferDuration = TimeSpan.FromMilliseconds(500),
                DiscardOnBufferOverflow = true
            };
            _output = new WaveOutEvent { DeviceNumber = Math.Max(0, DeviceCombo.SelectedIndex), DesiredLatency = 80 };
            _output.Init(_buffer);
            _output.Play();

            _udp = new UdpClient(Port);
            _cts = new CancellationTokenSource();
            var udp = _udp;
            var token = _cts.Token;
            _ = Task.Run(() => ReceiveLoop(udp, token));

            StartButton.Content = "STOP RECEIVER";
            DeviceCombo.IsEnabled = false;
        }
        catch (SocketException)
        {
            Stop();
            MessageBox.Show("Could not open network port " + Port +
                ". Another program may be using it, or Windows Firewall is blocking Phone Mic.\n" +
                "If Windows asks, choose 'Allow' for Private networks.", "Phone Mic");
        }
        catch (Exception)
        {
            Stop();
            MessageBox.Show("Could not open the selected audio device. Choose another output device and try again.", "Phone Mic");
        }
    }

    private async Task ReceiveLoop(UdpClient udp, CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                var r = await udp.ReceiveAsync(token);
                var d = r.Buffer;
                if (d.Length <= HeaderSize || d[0] != 'P' || d[1] != 'M' || d[2] != 'I' || d[3] != 'C') continue;
                if (BitConverter.ToInt32(d, 4) != _pinValue) continue; // ignore unpaired devices

                Interlocked.Exchange(ref _lastPacketTicks, DateTime.UtcNow.Ticks);
                _sender = r.RemoteEndPoint.Address.ToString();

                int count = (d.Length - HeaderSize) / 2;
                float gain = _muted ? 0f : _gain;
                int peak = 0;
                for (int i = 0; i < count; i++)
                {
                    int o = HeaderSize + i * 2;
                    int s = (short)(d[o] | (d[o + 1] << 8));
                    s = (int)Math.Clamp(s * gain, short.MinValue, short.MaxValue);
                    d[o] = (byte)(s & 0xFF);
                    d[o + 1] = (byte)((s >> 8) & 0xFF);
                    peak = Math.Max(peak, Math.Abs(s));
                }
                _buffer?.AddSamples(d, HeaderSize, count * 2);
                double level = peak / 32768.0 * 100.0;
                Dispatcher.BeginInvoke(() => LevelBar.Value = level);
            }
        }
        catch (OperationCanceledException) { }
        catch (ObjectDisposedException) { }
        catch (SocketException) { }
    }

    private void Stop()
    {
        _cts?.Cancel();
        _udp?.Close();
        _udp = null;
        _output?.Stop();
        _output?.Dispose();
        _output = null;
        _buffer = null;
        StartButton.Content = "START RECEIVER";
        DeviceCombo.IsEnabled = true;
        StatusText.Text = "● Stopped";
        StatusText.Foreground = Brushes.Gray;
        LevelBar.Value = 0;
    }

    private void VolumeSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        => _gain = (float)(e.NewValue / 100.0);

    private void MuteCheck_Changed(object sender, RoutedEventArgs e)
        => _muted = MuteCheck.IsChecked == true;
}
