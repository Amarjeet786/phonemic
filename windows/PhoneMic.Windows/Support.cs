using System;
using System.IO;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace PhoneMic.Windows;

public class AppSettings
{
    public string Pin { get; set; } = "";
    public string OutputDevice { get; set; } = "";
    public string MonitorDevice { get; set; } = "";
    public int Latency { get; set; } = 1; // 0 Ultra Low, 1 Low, 2 Balanced, 3 High Quality
    public double Volume { get; set; } = 100;
    public double MonitorVolume { get; set; } = 50;
    public bool MinimizeToTray { get; set; } = false;
    public bool Rnnoise { get; set; } = true;

    private static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhoneMic", "settings.json");

    public static AppSettings Load()
    {
        AppSettings s;
        try { s = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings(); }
        catch { s = new AppSettings(); }
        if (s.Pin.Length != 6) s.Pin = NewPin();
        return s;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this));
        }
        catch { }
    }

    public static string NewPin() => RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
}

public static class Crypto
{
    public static byte[] DeriveKey(string pin) =>
        Rfc2898DeriveBytes.Pbkdf2(pin, Encoding.UTF8.GetBytes("PhoneMic-v2"), 20000, HashAlgorithmName.SHA256, 32);
}

public static class NetUtil
{
    /// <summary>Stops Windows from raising an error on the receive socket when a peer is unreachable.</summary>
    public static void DisableConnReset(UdpClient u)
    {
        try { u.Client.IOControl((System.Net.Sockets.IOControlCode)(-1744830452), new byte[] { 0, 0, 0, 0 }, null); }
        catch { }
    }
}
