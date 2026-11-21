using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PocketPlayer;

internal static class Network
{
    public static IReadOnlyList<string> LocalAddresses() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up && adapter.NetworkInterfaceType is
            NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211)
        .Select(adapter => adapter.GetIPProperties())
        .OrderByDescending(properties => properties.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork))
        .SelectMany(properties => properties.UnicastAddresses)
        .Select(unicast => unicast.Address)
        .Where(IsLocalV4).Select(address => address.ToString()).Distinct().Take(8).ToArray();

    public static bool IsLocalV4(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10 || bytes[0] == 172 && bytes[1] is >= 16 and <= 31 ||
            bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 169 && bytes[1] == 254;
    }
}

internal sealed class Discovery
{
    private const uint Pending = 9506;
    private readonly string name;
    private readonly int port;
    private readonly object gate = new();
    private string? activeAddress;
    private IntPtr instance;
    private int generation;
    private long retryRegistrationAt;
    private bool closed;
    private static readonly Completion Completed = OnCompleted;

    public Discovery(string name, int port)
    {
        this.name = name;
        this.port = port;
    }

    public bool Advertised { get; private set; }

    public void Refresh(IReadOnlyList<string> addresses)
    {
        var address = addresses.FirstOrDefault();
        lock (gate)
        {
            if (closed || address == activeAddress && (instance != IntPtr.Zero || address is null)) return;
            if (address != null && address == activeAddress && Environment.TickCount64 < retryRegistrationAt) return;
            Unregister();
            if (address is null) return;
            activeAddress = address;
            var ipv4 = BitConverter.ToUInt32(IPAddress.Parse(address).GetAddressBytes());
            instance = Construct(name + "._pocketplayer._tcp.local", Environment.MachineName + ".local", ref ipv4,
                IntPtr.Zero, (ushort)port, 0, 0, 1, ["v"], ["1"]);
            if (instance == IntPtr.Zero) { retryRegistrationAt = Environment.TickCount64 + 5000; return; }
            var request = CreateRequest(instance, ++generation, this);
            if (Register(ref request, IntPtr.Zero) != Pending)
            {
                GCHandle.FromIntPtr(request.Context).Free();
                FreeInstance(instance);
                instance = IntPtr.Zero;
                retryRegistrationAt = Environment.TickCount64 + 5000;
            }
        }
    }

    public void Stop() { lock (gate) { closed = true; Unregister(); } }

    private void Unregister()
    {
        Advertised = false;
        generation++;
        if (instance == IntPtr.Zero) return;
        var request = CreateRequest(instance, generation, null);
        Deregister(ref request, IntPtr.Zero);
        FreeInstance(instance);
        instance = IntPtr.Zero;
    }

    private static Request CreateRequest(IntPtr instance, int generation, Discovery? owner) => new()
    {
        Version = 1,
        Instance = instance,
        Callback = Marshal.GetFunctionPointerForDelegate(Completed),
        Context = owner is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc((owner, generation))),
    };

    private static void OnCompleted(uint status, IntPtr context, IntPtr returnedInstance)
    {
        if (returnedInstance != IntPtr.Zero) FreeInstance(returnedInstance);
        if (context == IntPtr.Zero) return;
        var handle = GCHandle.FromIntPtr(context);
        var (owner, generation) = ((Discovery, int))handle.Target!;
        handle.Free();
        lock (owner.gate)
        {
            if (owner.generation != generation) return;
            owner.Advertised = status == 0;
            if (!owner.Advertised)
            {
                if (owner.instance != IntPtr.Zero) FreeInstance(owner.instance);
                owner.instance = IntPtr.Zero;
                owner.retryRegistrationAt = Environment.TickCount64 + 5000;
            }
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Request
    {
        public uint Version, InterfaceIndex;
        public IntPtr Instance, Callback, Context, Credentials;
        public int Unicast;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void Completion(uint status, IntPtr context, IntPtr instance);
    [DllImport("dnsapi.dll", EntryPoint = "DnsServiceConstructInstance", CharSet = CharSet.Unicode)]
    private static extern IntPtr Construct(string service, string host, ref uint ip, IntPtr ip6, ushort port,
        ushort priority, ushort weight, uint properties,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] keys,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] values);
    [DllImport("dnsapi.dll", EntryPoint = "DnsServiceRegister")]
    private static extern uint Register(ref Request request, IntPtr cancel);
    [DllImport("dnsapi.dll", EntryPoint = "DnsServiceDeRegister")]
    private static extern uint Deregister(ref Request request, IntPtr cancel);
    [DllImport("dnsapi.dll", EntryPoint = "DnsServiceFreeInstance")]
    private static extern void FreeInstance(IntPtr instance);
}
