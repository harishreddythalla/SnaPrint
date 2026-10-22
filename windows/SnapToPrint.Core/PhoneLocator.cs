using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace SnapToPrint.Core;

/// <summary>Finds the phone on the Wi-Fi adapter only: its gateway / DHCP server, never a blind subnet scan.</summary>
public static class PhoneLocator
{
    public const int Port = 8765;

    public static IEnumerable<NetworkInterface> WifiInterfaces() =>
        NetworkInterface.GetAllNetworkInterfaces().Where(n =>
            n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211);

    public static List<IPAddress> Candidates(string? manualIp, string? lastHost)
    {
        var seen = new HashSet<string>();
        var list = new List<IPAddress>();
        void Add(IPAddress? a)
        {
            if (a != null && a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.Any.Equals(a) && seen.Add(a.ToString())) list.Add(a);
        }
        if (IPAddress.TryParse(manualIp?.Trim(), out var m)) Add(m);
        foreach (var ni in WifiInterfaces())
        {
            var p = ni.GetIPProperties();
            foreach (var g in p.GatewayAddresses) Add(g.Address);
            foreach (var d in p.DhcpServerAddresses) Add(d);
        }
        if (IPAddress.TryParse(lastHost, out var l)) Add(l);
        return list;
    }

    public static string Describe()
    {
        var sb = new StringBuilder();
        var any = false;
        foreach (var ni in WifiInterfaces())
        {
            any = true;
            var p = ni.GetIPProperties();
            sb.AppendLine($"Wi-Fi adapter: {ni.Name}");
            sb.AppendLine("  addresses: " + string.Join(", ", p.UnicastAddresses.Where(u => u.Address.AddressFamily == AddressFamily.InterNetwork).Select(u => u.Address)));
            sb.AppendLine("  gateways: " + string.Join(", ", p.GatewayAddresses.Select(g => g.Address)));
        }
        if (!any) sb.AppendLine("No connected Wi-Fi adapter found. Connect the laptop to the phone's hotspot.");
        return sb.ToString();
    }
}
