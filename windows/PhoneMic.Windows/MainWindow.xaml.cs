using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;
using Concentus;
using NAudio.Wave;

namespace PhoneMic.Windows;

/// <summary>
/// Receiver: UDP (encrypted Opus) -> Opus decode (+PLC) -> RNNoise -> jitter buffer -> selected output device (+ optional monitor + WAV recording).
/// Packet = "PMC2"(4) codec(1) session(4 LE) seq(4 LE) + AES-GCM(payload)+tag(16).
/// </summary>
public partial class MainWindow : Window
{
    private const int Port = 50505;
    private const int DiscoveryPort = 50506;
    private const int HeaderSize = 13;
    private const int BytesPerMs = 96; // 48 kHz * 16 bit * mono
    private static readonly int[] LatencyTargets = { 20, 40, 80, 140 };
    private static readonly int[] OutLatencies = { 30, 40, 60, 100 };

    private readonly AppSettings _settings = AppSettings.Load();
    private bool _loading = true;

    private UdpClient? _discovery;
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private AesGcm? _aes;
    private BufferedWaveProvider? _buffer;
    private BufferedWaveProvider? _monBuffer;
    private WaveOutEvent? _output;
    private WaveOutEvent? _monOutput;

    private volatile float _gain = 1.0f;
    private volatile float _monGain = 0.5f;
    private volatile bool _muted;
    private volatile bool _monitor;
    private volatile int _targetMs = 40;
    private volatile int _outLatencyMs = 40;
    private volatile int _extraMs;

    private IOpusDecoder? _opus;
    private int _lastFrame = 960;
    private double _frameMs = 20;
    private RnnoiseDenoiser? _rnnoise;
    private volatile bool _rnnoiseOn;

    private readonly object _statLock = new();
    private long _lastPacketTicks;
    private string _sender = "";
    private uint _session;
    private bool _haveSession;
    private long _lastSeq = -1;
    private long _received;
    private long _lost;
    private double _jitter;
    private double _lastArrivalMs = -1;
    private long _bytesWindow;
    private int _underruns;
    private long _lastAckMs;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private double _lastStatsMs;

    private readonly object _recLock = new();
    private WaveFileWriter? _rec;
    private DateTime _recStart;
    private string _recPath = "";

    private System.Windows.Forms.NotifyIcon? _tray;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private int _tick;

    public MainWindow()
    {
        InitializeComponent();

        PinText.Text = "PIN: " + _settings.Pin;
        ShowIps();

        LoadDevices(_settings.OutputDevice, _settings.MonitorDevice);

        LatencyCombo.SelectedIndex = Math.Clamp(_settings.Latency, 0, 3);
        _targetMs = LatencyTargets[LatencyCombo.SelectedIndex];
        _outLatencyMs = OutLatencies[LatencyCombo.SelectedIndex];
        VolumeSlider.Value = _settings.Volume;
        _gain = (float)(_settings.Volume / 100.0);
        MonitorSlider.Value = _settings.MonitorVolume;
        _monGain = (float)(_settings.MonitorVolume / 100.0);
        TrayCheck.IsChecked = _settings.MinimizeToTray;
        _rnnoise = RnnoiseDenoiser.TryCreate();
        if (_rnnoise == null)
        {
            RnnoiseCheck.IsEnabled = false;
            RnnoiseCheck.IsChecked = false;
            RnnoiseCheck.Content = "AI noise removal (RNNoise): not available on this PC";
        }
        else
        {
            RnnoiseCheck.IsChecked = _settings.Rnnoise;
            _rnnoiseOn = _settings.Rnnoise;
        }
        AutoStartCheck.IsChecked = IsAutoStartEnabled();

        SetupTray();
        StartDiscoveryResponder();

        _timer.Tick += (_, _) => OnTimer();
        _timer.Start();

        StateChanged += (_, _) =>
        {
            if (WindowState == WindowState.Minimized && _settings.MinimizeToTray) HideToTray();
        };
        Closed += (_, _) => Shutdown();

        _loading = false;

        bool trayLaunch = Environment.GetCommandLineArgs().Contains("--tray");
        if (trayLaunch)
        {
            Loaded += (_, _) =>
            {
                StartReceiver();
                HideToTray();
            };
        }
    }

    // ---------- setup helpers ----------

    private static int PickDevice(List<string> devices, string saved, Func<string, bool> fallback)
    {
        int i = devices.FindIndex(d => d == saved);
        if (i >= 0) return i;
        i = devices.FindIndex(d => fallback(d));
        if (i >= 0) return i;
        return devices.Count > 0 ? 0 : -1;
    }

    private void LoadDevices(string wantOut, string wantMon)
    {
        var devices = new List<string>();
        for (int i = 0; i < WaveOut.DeviceCount; i++) devices.Add(WaveOut.GetCapabilities(i).ProductName);
        DeviceCombo.ItemsSource = devices;
        MonitorCombo.ItemsSource = devices;
        DeviceCombo.SelectedIndex = PickDevice(devices, wantOut, d => d.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase));
        MonitorCombo.SelectedIndex = PickDevice(devices, wantMon, d => !d.Contains("CABLE", StringComparison.OrdinalIgnoreCase));
        bool hasCable = devices.Any(d => d.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase));
        CableWarn.Visibility = hasCable ? Visibility.Collapsed : Visibility.Visible;
    }

    private void RefreshDevices_Click(object sender, RoutedEventArgs e)
    {
        if (_udp != null) return; // stop the receiver first
        LoadDevices(DeviceCombo.SelectedItem as string ?? "", MonitorCombo.SelectedItem as string ?? "");
        ShowIps();
    }

    private void ShowIps()
    {
        var ips = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                        n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(a => n.Name + ": " + a.Address));
        IpText.Text = "IP: " + string.Join("\n", ips);
    }

    private void SetupTray()
    {
        try
        {
            _tray = new System.Windows.Forms.NotifyIcon
            {
                Icon = System.Drawing.SystemIcons.Application,
                Text = "Phone Mic",
                Visible = false
            };
            _tray.DoubleClick += (_, _) => ShowFromTray();
            var menu = new System.Windows.Forms.ContextMenuStrip();
            menu.Items.Add("Open Phone Mic", null, (_, _) => ShowFromTray());
            menu.Items.Add("Exit", null, (_, _) => Close());
            _tray.ContextMenuStrip = menu;
        }
        catch { _tray = null; }
    }

    private void HideToTray()
    {
        if (_tray == null) return;
        _tray.Visible = true;
        Hide();
    }

    private void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
        if (_tray != null) _tray.Visible = false;
    }

    // ---------- Wi-Fi / cable / Bluetooth-tether discovery ----------

    private void StartDiscoveryResponder()
    {
        try
        {
            var u = new UdpClient { ExclusiveAddressUse = false };
            u.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            u.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
            NetUtil.DisableConnReset(u);
            _discovery = u;
            _ = Task.Run(async () =>
            {
                while (true)
                {
                    try
                    {
                        var r = await u.ReceiveAsync();
                        if (Encoding.UTF8.GetString(r.Buffer) != "PMIC_DISCOVER") continue;
                        var reply = Encoding.UTF8.GetBytes($"PMIC_HERE|{Environment.MachineName}|{Port}");
                        await u.SendAsync(reply, reply.Length, r.RemoteEndPoint);
                    }
                    catch (ObjectDisposedException) { break; }
                    catch (Exception) { }
                }
            });
        }
        catch (SocketException) { }
    }

    // ---------- start / stop ----------

    private void StartButton_Click(object sender, RoutedEventArgs e)
    {
        if (_udp != null) StopReceiver(); else StartReceiver();
    }

    private void StartReceiver()
    {
        if (_udp != null) return;
        try
        {
            _aes = new AesGcm(Crypto.DeriveKey(_settings.Pin), 16);
            var fmt = new WaveFormat(48000, 16, 1);
            _outLatencyMs = OutLatencies[Math.Clamp(LatencyCombo.SelectedIndex, 0, 3)];

            _buffer = new BufferedWaveProvider(fmt) { BufferDuration = TimeSpan.FromMilliseconds(1000), DiscardOnBufferOverflow = true };
            _output = new WaveOutEvent { DeviceNumber = Math.Max(0, DeviceCombo.SelectedIndex), DesiredLatency = _outLatencyMs };
            _output.Init(_buffer);
            _output.Play();

            try
            {
                _monBuffer = new BufferedWaveProvider(fmt) { BufferDuration = TimeSpan.FromMilliseconds(1000), DiscardOnBufferOverflow = true };
                _monOutput = new WaveOutEvent { DeviceNumber = Math.Max(0, MonitorCombo.SelectedIndex), DesiredLatency = 80 };
                _monOutput.Init(_monBuffer);
                _monOutput.Play();
            }
            catch
            {
                _monOutput?.Dispose();
                _monOutput = null;
                _monBuffer = null;
            }

            lock (_statLock)
            {
                _haveSession = false; _lastSeq = -1; _received = 0; _lost = 0; _jitter = 0;
                _lastArrivalMs = -1; _bytesWindow = 0; _underruns = 0; _extraMs = 0;
            }
            Interlocked.Exchange(ref _lastPacketTicks, 0);
            _opus = null;
            _lastFrame = 960;
            _frameMs = 20;

            _udp = new UdpClient(Port);
            NetUtil.DisableConnReset(_udp);
            _cts = new CancellationTokenSource();
            var udp = _udp;
            var token = _cts.Token;
            _ = Task.Run(() => ReceiveLoop(udp, token));

            StartButton.Content = "STOP RECEIVER";
            DeviceCombo.IsEnabled = false;
            MonitorCombo.IsEnabled = false;
            NewPinButton.IsEnabled = false;
            StatusText.Text = "● Waiting for phone...";
            StatusText.Foreground = Brushes.Gray;
        }
        catch (SocketException)
        {
            StopReceiver();
            MessageBox.Show("Could not open network port " + Port +
                ". Another program may be using it, or Windows Firewall is blocking Phone Mic.\n" +
                "If Windows asks, choose 'Allow' (Private and Public networks).", "Phone Mic");
        }
        catch (Exception)
        {
            StopReceiver();
            MessageBox.Show("Could not open the selected audio device. Choose another output device and try again.", "Phone Mic");
        }
    }

    private void StopReceiver()
    {
        _cts?.Cancel();
        _udp?.Close();
        _udp = null;
        _output?.Stop(); _output?.Dispose(); _output = null;
        _monOutput?.Stop(); _monOutput?.Dispose(); _monOutput = null;
        _buffer = null;
        _monBuffer = null;
        _aes?.Dispose();
        _aes = null;
        _opus = null;
        StopRecording();
        StartButton.Content = "START RECEIVER";
        DeviceCombo.IsEnabled = true;
        MonitorCombo.IsEnabled = true;
        NewPinButton.IsEnabled = true;
        StatusText.Text = "● Stopped";
        StatusText.Foreground = Brushes.Gray;
        StatsText.Text = "";
        LevelBar.Value = 0;
    }

    private void Shutdown()
    {
        SaveSettings();
        StopReceiver();
        try { _discovery?.Close(); } catch { }
        try { _rnnoise?.Dispose(); } catch { }
        if (_tray != null) { _tray.Visible = false; _tray.Dispose(); }
    }

    private void SaveSettings()
    {
        if (DeviceCombo.SelectedItem is string d) _settings.OutputDevice = d;
        if (MonitorCombo.SelectedItem is string m) _settings.MonitorDevice = m;
        _settings.Latency = Math.Max(0, LatencyCombo.SelectedIndex);
        _settings.Volume = VolumeSlider.Value;
        _settings.MonitorVolume = MonitorSlider.Value;
        _settings.MinimizeToTray = TrayCheck.IsChecked == true;
        _settings.Rnnoise = RnnoiseCheck.IsChecked == true;
        _settings.Save();
    }

    // ---------- receive path ----------

    private async Task ReceiveLoop(UdpClient udp, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                var r = await udp.ReceiveAsync(token);
                HandlePacket(udp, r.Buffer, r.RemoteEndPoint);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { if (token.IsCancellationRequested) break; }
            catch (Exception) { }
        }
    }

    private void HandlePacket(UdpClient udp, byte[] d, IPEndPoint from)
    {
        if (d.Length < HeaderSize + 16 + 4) return;
        if (d[0] != 'P' || d[1] != 'M' || d[2] != 'C' || d[3] != '2') return;
        byte codec = d[4];
        uint session = BitConverter.ToUInt32(d, 5);
        uint seq = BitConverter.ToUInt32(d, 9);

        var aes = _aes;
        var buf = _buffer;
        if (aes == null || buf == null) return;

        // Authenticate + decrypt. Wrong PIN or tampered packets are dropped silently.
        int cipherLen = d.Length - HeaderSize - 16;
        var nonce = new byte[12];
        Array.Copy(d, 5, nonce, 0, 8);
        var plain = new byte[cipherLen];
        try
        {
            aes.Decrypt(nonce, d.AsSpan(HeaderSize, cipherLen), d.AsSpan(HeaderSize + cipherLen, 16), plain, d.AsSpan(0, HeaderSize));
        }
        catch (CryptographicException) { return; }

        double nowMs = _clock.Elapsed.TotalMilliseconds;
        long gap = 0;
        lock (_statLock)
        {
            if (!_haveSession || session != _session)
            {
                _session = session; _haveSession = true; _lastSeq = -1; _opus = null;
                _received = 0; _lost = 0; _jitter = 0; _lastArrivalMs = -1;
            }
            if (seq <= _lastSeq) return; // replay / duplicate / late
            if (_lastSeq >= 0 && seq > _lastSeq + 1) { gap = seq - _lastSeq - 1; _lost += gap; }
            _lastSeq = seq;
            _received++;
            if (_lastArrivalMs >= 0)
            {
                double dev = Math.Abs((nowMs - _lastArrivalMs) - _frameMs);
                _jitter += (dev - _jitter) / 16.0;
            }
            _lastArrivalMs = nowMs;
            _bytesWindow += d.Length;
        }

        Interlocked.Exchange(ref _lastPacketTicks, DateTime.UtcNow.Ticks);
        _sender = from.Address.ToString();

        // Small ACK so the phone knows the link is alive.
        if (nowMs - _lastAckMs > 500)
        {
            _lastAckMs = (long)nowMs;
            try
            {
                var ack = new byte[8];
                ack[0] = (byte)'P'; ack[1] = (byte)'M'; ack[2] = (byte)'A'; ack[3] = (byte)'K';
                Array.Copy(d, 5, ack, 4, 4);
                udp.Send(ack, ack.Length, from);
            }
            catch { }
        }

        if (codec != 2) return; // 2 = Opus
        var frames = new List<short[]>(2);
        try
        {
            var dec = _opus ??= OpusCodecFactory.CreateDecoder(48000, 1);

            // Conceal short gaps with Opus packet-loss concealment so a lost packet does not click.
            if (gap > 0 && gap <= 3 && _lastFrame > 0)
            {
                for (long g = 0; g < gap; g++)
                {
                    var plc = new short[_lastFrame];
                    int pn = dec.Decode(ReadOnlySpan<byte>.Empty, plc.AsSpan(), _lastFrame);
                    if (pn > 0) frames.Add(pn == plc.Length ? plc : plc.AsSpan(0, pn).ToArray());
                }
            }

            var pcmBuf = new short[5760];
            int n = dec.Decode(plain.AsSpan(), pcmBuf.AsSpan(), 5760);
            if (n <= 0) return;
            _lastFrame = n;
            _frameMs = n / 48.0;
            frames.Add(pcmBuf.AsSpan(0, n).ToArray());
        }
        catch (Exception) { return; }

        foreach (var f in frames) Emit(f);
    }

    /// <summary>RNNoise -> volume/mute -> jitter buffer -> output, monitor, recording, level meter.</summary>
    private void Emit(short[] pcm)
    {
        var buf = _buffer;
        if (buf == null || pcm.Length == 0) return;

        if (_rnnoiseOn) _rnnoise?.Process(pcm, pcm.Length);

        float gain = _muted ? 0f : _gain;
        var main = new byte[pcm.Length * 2];
        int peak = 0;
        for (int i = 0; i < pcm.Length; i++)
        {
            int s = (int)Math.Clamp(pcm[i] * gain, short.MinValue, short.MaxValue);
            main[i * 2] = (byte)(s & 0xFF);
            main[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
            peak = Math.Max(peak, Math.Abs(s));
        }

        // Jitter buffer: prefill after an underrun, drop packets if we drift too far ahead.
        int targetBytes = (_targetMs + _extraMs) * BytesPerMs;
        int buffered = buf.BufferedBytes;
        if (buffered < 5 * BytesPerMs)
        {
            long rc;
            lock (_statLock) rc = _received;
            if (rc > 3)
            {
                lock (_statLock) _underruns++;
                _extraMs = Math.Min(_extraMs + 10, 100);
            }
            var silence = new byte[targetBytes];
            buf.AddSamples(silence, 0, silence.Length);
        }
        else if (buffered > targetBytes + 60 * BytesPerMs)
        {
            return; // too far ahead: drop this frame to keep latency bounded
        }
        buf.AddSamples(main, 0, main.Length);

        if (_monitor)
        {
            var mb = _monBuffer;
            if (mb != null)
            {
                var mon = new byte[pcm.Length * 2];
                float mg = _monGain;
                for (int i = 0; i < pcm.Length; i++)
                {
                    int s = (int)Math.Clamp(pcm[i] * mg, short.MinValue, short.MaxValue);
                    mon[i * 2] = (byte)(s & 0xFF);
                    mon[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
                }
                if (mb.BufferedBytes < 200 * BytesPerMs) mb.AddSamples(mon, 0, mon.Length);
            }
        }

        lock (_recLock) { _rec?.Write(main, 0, main.Length); }

        double level = peak / 32768.0 * 100.0;
        Dispatcher.BeginInvoke(new Action(() => LevelBar.Value = level));
    }

    // ---------- status / stats ----------

    private void OnTimer()
    {
        _tick++;
        if (_udp == null) return;

        var age = DateTime.UtcNow - new DateTime(Interlocked.Read(ref _lastPacketTicks), DateTimeKind.Utc);
        bool live = Interlocked.Read(ref _lastPacketTicks) != 0 && age < TimeSpan.FromSeconds(2);
        if (live)
        {
            StatusText.Text = "● Connected: " + _sender;
            StatusText.Foreground = Brushes.Green;
        }
        else
        {
            StatusText.Text = "● Waiting for phone...";
            StatusText.Foreground = Brushes.Gray;
            LevelBar.Value = 0;
            StatsText.Text = "";
        }

        if (live)
        {
            long rec, lost; double jitter; long bytes; int underruns;
            double nowMs = _clock.Elapsed.TotalMilliseconds;
            lock (_statLock)
            {
                rec = _received; lost = _lost; jitter = _jitter; bytes = _bytesWindow; underruns = _underruns;
                _bytesWindow = 0;
            }
            double secs = Math.Max(0.1, (nowMs - _lastStatsMs) / 1000.0);
            _lastStatsMs = nowMs;
            double kbps = bytes * 8 / 1000.0 / secs;
            double lossPct = (rec + lost) > 0 ? 100.0 * lost / (rec + lost) : 0;
            var buf = _buffer;
            double bufMs = buf != null ? buf.BufferedBytes / (double)BytesPerMs : 0;
            double latency = bufMs + _outLatencyMs + _frameMs + 10; // buffer + output device + frame + network (approximate)
            string quality = (lossPct < 1 && jitter < 10) ? "Excellent" : (lossPct < 3 && jitter < 25) ? "Good" : "Poor";
            StatsText.Text = $"Connection: {quality}   Latency ≈ {latency:0} ms\n" +
                             $"Packet loss: {lossPct:0.0}%   Jitter: {jitter:0} ms   Bitrate: {kbps:0} kbps   Underruns: {underruns}";
        }

        // Slowly relax the extra buffering when the network is calm again.
        if (_tick % 10 == 0 && _extraMs > 0) _extraMs = Math.Max(0, _extraMs - 5);

        lock (_recLock)
        {
            if (_rec != null)
            {
                var t = DateTime.Now - _recStart;
                RecordText.Text = $"Recording {t:hh\\:mm\\:ss}\n{_recPath}";
            }
        }
    }

    // ---------- recording ----------

    private void RecordButton_Click(object sender, RoutedEventArgs e)
    {
        bool recording;
        lock (_recLock) recording = _rec != null;
        if (recording) { StopRecording(); return; }
        if (_udp == null)
        {
            MessageBox.Show("Start the receiver first, then press Record.", "Phone Mic");
            return;
        }
        try
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "PhoneMic Recordings");
            Directory.CreateDirectory(dir);
            string path = Path.Combine(dir, "PhoneMic_" + DateTime.Now.ToString("yyyyMMdd_HHmmss") + ".wav");
            lock (_recLock)
            {
                _rec = new WaveFileWriter(path, new WaveFormat(48000, 16, 1));
                _recStart = DateTime.Now;
                _recPath = path;
            }
            RecordButton.Content = "■ Stop recording";
        }
        catch
        {
            MessageBox.Show("Could not create the recording file.", "Phone Mic");
        }
    }

    private void StopRecording()
    {
        string saved = "";
        lock (_recLock)
        {
            if (_rec != null)
            {
                _rec.Dispose();
                _rec = null;
                saved = _recPath;
            }
        }
        RecordButton.Content = "● Record";
        if (saved != "") RecordText.Text = "Saved: " + saved;
    }

    // ---------- UI handlers ----------

    private void NewPinButton_Click(object sender, RoutedEventArgs e)
    {
        _settings.Pin = AppSettings.NewPin();
        PinText.Text = "PIN: " + _settings.Pin;
        _settings.Save();
    }

    private void LatencyCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading || LatencyCombo.SelectedIndex < 0) return;
        _targetMs = LatencyTargets[LatencyCombo.SelectedIndex];
        if (_udp == null) _outLatencyMs = OutLatencies[LatencyCombo.SelectedIndex]; // device latency applies on next start
    }

    private void VolumeSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        => _gain = (float)(e.NewValue / 100.0);

    private void MonitorSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        => _monGain = (float)(e.NewValue / 100.0);

    private void MuteCheck_Changed(object sender, RoutedEventArgs e)
        => _muted = MuteCheck.IsChecked == true;

    private void MonitorCheck_Changed(object sender, RoutedEventArgs e)
        => _monitor = MonitorCheck.IsChecked == true;

    private void RnnoiseCheck_Changed(object sender, RoutedEventArgs e)
    {
        _rnnoiseOn = RnnoiseCheck.IsChecked == true && _rnnoise != null;
    }

    private void TrayCheck_Changed(object sender, RoutedEventArgs e)
    {
        _settings.MinimizeToTray = TrayCheck.IsChecked == true;
        if (!_loading) _settings.Save();
    }

    private void AutoStartCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        SetAutoStart(AutoStartCheck.IsChecked == true);
    }

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    private static bool IsAutoStartEnabled()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue("PhoneMic") != null;
        }
        catch { return false; }
    }

    // Only changes anything when the user ticks the box (explicit consent).
    private static void SetAutoStart(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true);
            if (k == null) return;
            if (on) k.SetValue("PhoneMic", "\"" + Environment.ProcessPath + "\" --tray");
            else k.DeleteValue("PhoneMic", false);
        }
        catch { }
    }
}
