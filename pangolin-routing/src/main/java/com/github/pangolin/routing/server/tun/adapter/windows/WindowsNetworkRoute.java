package com.github.pangolin.routing.server.tun.adapter.windows;

import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHelpLib.INSTANCE;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHelpLib.MIB_IPFORWARD_ROW2;

import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 *
 */
public class WindowsNetworkRoute {
    /**
     * A static route. This value is used to identify route information
     * for IP routing set through network management such as the Dynamic
     * Host Configuration Protocol (DCHP), the Simple Network Management
     * Protocol (SNMP), or by calls to the CreateIpForwardEntry2,
     * DeleteIpForwardEntry2, or SetIpForwardEntry2 functions.
     *
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/ns-netioapi-mib_ipforward_row2">MIB_IPFORWARD_ROW2</a>
     */
    private static final int MIB_IPPROTO_NETMGMT = 3;


    public static void add(final InetAddress dst, final byte prefix, final InetAddress gw, final long ifaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        INSTANCE.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = ifaceLuid;
        row.Loopback = new WinDef.BOOL(0);
        row.ValidLifetime = 0xFFFFFFFF;
        row.PreferredLifetime = 0xFFFFFFFF;
        // row.Metric = 10;

        row.DestinationPrefix.Prefix = WindowsUtils.toSockAddr(dst);
        row.DestinationPrefix.PrefixLength = prefix;

        row.NextHop = WindowsUtils.toSockAddr(gw);

        final int code = INSTANCE.CreateIpForwardEntry2(row);
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    public static void delete(final InetAddress dst, final byte prefix, final long ifaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        INSTANCE.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = ifaceLuid;
        row.DestinationPrefix.Prefix = WindowsUtils.toSockAddr(dst);
        row.DestinationPrefix.PrefixLength = prefix;

        final int code = INSTANCE.DeleteIpForwardEntry2(row);
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    private static MIB_IPFORWARD_ROW2 getMibIpForwardRow2(final InetAddress dst, final byte prefix) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        INSTANCE.InitializeIpForwardEntry(row);

        row.DestinationPrefix.Prefix = WindowsUtils.toSockAddr(dst);
        row.DestinationPrefix.PrefixLength = prefix;

        final int code = INSTANCE.GetIpForwardEntry2(row);
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
        return row;
    }

    public static void main(String[] args) throws SocketException, UnknownHostException {
//        final Enumeration<NetworkInterface> nix = NetworkInterface.getNetworkInterfaces();
//        while (nix.hasMoreElements()) {
//            NetworkInterface ni = nix.nextElement();
//            System.out.println(ni.getIndex() + " -> " + ni.getName() + " -> " + ni.getDisplayName() + " -> " + ni.getParent());
//        }
        final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByAlias("以太网 2");
        long luid = nix.luid();
        InetAddress dst = InetAddress.getByName("198.18.0.0");
        int prefix = (byte) 24;
        InetAddress gw = InetAddress.getByName("10.188.70.1");

//         add(dst, (byte) prefix, gw, luid);
//        get(dst, (byte) prefix);
//        delete(dst, (byte) prefix, luid);
    }
}
