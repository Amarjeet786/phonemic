using System;
using System.IO;
using System.Runtime.InteropServices;

namespace PhoneMic.Windows;

/// <summary>
/// RNNoise (recurrent-neural-network noise suppression) via the native library shipped in the
/// YellowDogMan.RRNoise.NET package. Optional: if the library cannot be loaded, TryCreate returns null
/// and the app keeps working without it. Works on 48 kHz mono, 480-sample frames.
/// </summary>
public sealed class RnnoiseDenoiser : IDisposable
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate IntPtr CreateFn(IntPtr model);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate float ProcessFn(IntPtr state, float[] output, float[] input);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void DestroyFn(IntPtr state);

    private readonly IntPtr _state;
    private readonly ProcessFn _process;
    private readonly DestroyFn _destroy;
    private readonly float[] _in = new float[480];
    private readonly float[] _out = new float[480];

    private RnnoiseDenoiser(IntPtr state, ProcessFn process, DestroyFn destroy)
    {
        _state = state;
        _process = process;
        _destroy = destroy;
    }

    public static RnnoiseDenoiser? TryCreate()
    {
        try
        {
            IntPtr lib = IntPtr.Zero;
            foreach (var name in new[] { "rnnoise", "librnnoise", "rnnoise_x64", "rnnoise-0" })
            {
                if (NativeLibrary.TryLoad(name, typeof(RnnoiseDenoiser).Assembly, null, out lib)) break;
                lib = IntPtr.Zero;
            }
            if (lib == IntPtr.Zero)
            {
                foreach (var dir in new[] { AppContext.BaseDirectory, Path.GetDirectoryName(Environment.ProcessPath) ?? "" })
                {
                    if (dir.Length == 0 || !Directory.Exists(dir)) continue;
                    foreach (var f in Directory.GetFiles(dir, "*rnnoise*.dll", SearchOption.AllDirectories))
                    {
                        if (NativeLibrary.TryLoad(f, out lib)) break;
                        lib = IntPtr.Zero;
                    }
                    if (lib != IntPtr.Zero) break;
                }
            }
            if (lib == IntPtr.Zero) return null;

            if (!NativeLibrary.TryGetExport(lib, "rnnoise_create", out var pc)) return null;
            if (!NativeLibrary.TryGetExport(lib, "rnnoise_process_frame", out var pp)) return null;
            if (!NativeLibrary.TryGetExport(lib, "rnnoise_destroy", out var pd)) return null;

            var create = Marshal.GetDelegateForFunctionPointer<CreateFn>(pc);
            var process = Marshal.GetDelegateForFunctionPointer<ProcessFn>(pp);
            var destroy = Marshal.GetDelegateForFunctionPointer<DestroyFn>(pd);
            var state = create(IntPtr.Zero);
            if (state == IntPtr.Zero) return null;
            return new RnnoiseDenoiser(state, process, destroy);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Denoises in place. Only whole 480-sample blocks are processed.</summary>
    public void Process(short[] pcm, int count)
    {
        for (int o = 0; o + 480 <= count; o += 480)
        {
            for (int i = 0; i < 480; i++) _in[i] = pcm[o + i];
            _process(_state, _out, _in);
            for (int i = 0; i < 480; i++) pcm[o + i] = (short)Math.Clamp(_out[i], -32768f, 32767f);
        }
    }

    public void Dispose()
    {
        try { _destroy(_state); } catch { }
    }
}
