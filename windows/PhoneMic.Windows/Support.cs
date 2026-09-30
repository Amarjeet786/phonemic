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

/// <summary>IMA ADPCM decoder. Payload = pred(int16 LE) + index(byte) + 4-bit codes.</summary>
public static class Adpcm
{
    private static readonly int[] Steps =
    {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88,
        97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
        724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660,
        4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818,
        18500, 20350, 22385, 24623, 27086, 29794, 32767
    };
    private static readonly int[] Idx = { -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8 };

    public static short[] Decode(byte[] data, int offset, int length)
    {
        int pred = (short)(data[offset] | (data[offset + 1] << 8));
        int index = Math.Clamp((int)data[offset + 2], 0, 88);
        int count = (length - 3) * 2;
        var result = new short[count];
        for (int i = 0; i < count; i++)
        {
            int b = data[offset + 3 + i / 2];
            int code = (i % 2 == 0) ? (b & 0x0F) : ((b >> 4) & 0x0F);
            int step = Steps[index];
            int vp = step >> 3;
            if ((code & 4) != 0) vp += step;
            if ((code & 2) != 0) vp += step >> 1;
            if ((code & 1) != 0) vp += step >> 2;
            pred = (code & 8) != 0 ? pred - vp : pred + vp;
            pred = Math.Clamp(pred, -32768, 32767);
            index = Math.Clamp(index + Idx[code], 0, 88);
            result[i] = (short)pred;
        }
        return result;
    }
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
