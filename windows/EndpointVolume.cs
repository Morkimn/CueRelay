using System.Runtime.InteropServices;

namespace PocketPlayer;

internal static class EndpointVolume
{
    private static readonly Guid EnumeratorId = new("BCDE0395-E52F-467C-8E3D-C4579291692E");
    private static readonly Guid VolumeId = typeof(IEndpointVolume).GUID;

    public static int? Read() => Access(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.GetScalar(out float scalar));
        return (int)Math.Round(scalar * 100);
    });

    public static bool Set(int value) => value is >= 0 and <= 100 && Access(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.SetScalar(value / 100f, IntPtr.Zero));
        return value;
    }).HasValue;

    private static int? Access(Func<IEndpointVolume, int> use)
    {
        IDeviceEnumerator? enumerator = null;
        IDevice? device = null;
        object? endpoint = null;
        try
        {
            enumerator = (IDeviceEnumerator)Activator.CreateInstance(Type.GetTypeFromCLSID(EnumeratorId, throwOnError: true)!)!;
            if (enumerator.GetDefaultAudioEndpoint(0, 1, out device) < 0) return null;
            var id = VolumeId;
            if (device.Activate(ref id, 23, IntPtr.Zero, out endpoint) < 0) return null;
            return use((IEndpointVolume)endpoint);
        }
        catch (COMException) { return null; }
        finally
        {
            if (endpoint is not null) Marshal.ReleaseComObject(endpoint);
            if (device is not null) Marshal.ReleaseComObject(device);
            if (enumerator is not null) Marshal.ReleaseComObject(enumerator);
        }
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int flow, uint mask, out IntPtr collection);
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IDevice endpoint);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDevice
    {
        [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr activation, [MarshalAs(UnmanagedType.IUnknown)] out object result);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(IntPtr callback);
        [PreserveSig] int UnregisterControlChangeNotify(IntPtr callback);
        [PreserveSig] int GetChannelCount(out uint channels);
        [PreserveSig] int SetMasterVolumeLevel(float level, IntPtr eventContext);
        [PreserveSig] int SetScalar(float level, IntPtr eventContext);
        [PreserveSig] int GetMasterVolumeLevel(out float level);
        [PreserveSig] int GetScalar(out float level);
    }
}
