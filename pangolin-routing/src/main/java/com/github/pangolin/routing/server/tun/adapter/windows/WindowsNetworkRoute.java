package com.github.pangolin.routing.server.tun.adapter.windows;

import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHelpLib.INSTANCE;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHelpLib.MIB_IPFORWARD_ROW2;

import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

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

    private static MIB_IPFORWARD_ROW2 createMibIpForwardRow2(final InetAddress dst, final byte prefix, final InetAddress gw, final long ifaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        INSTANCE.InitializeIpForwardEntry(row);

        // 目标地址
        row.DestinationPrefix.Prefix = WindowsUtils.toSockAddr(dst);
        row.DestinationPrefix.PrefixLength = prefix;

        // 下一跳
        if (null != gw) {
            row.NextHop = WindowsUtils.toSockAddr(gw);
        }

        // 绑定网卡
        row.InterfaceLuid = ifaceLuid;

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.Metric = 10;
        row.ValidLifetime = 0xFFFFFFFF;
        row.PreferredLifetime = 0xFFFFFFFF;
        row.Loopback = new WinDef.BOOL(0);
        return row;
    }

    public static void add(final InetAddress dst, final byte prefix, final InetAddress gw, final long ifaceLuid) {
        final MIB_IPFORWARD_ROW2 row = createMibIpForwardRow2(dst, prefix, gw, ifaceLuid);
        final int code = INSTANCE.CreateIpForwardEntry2(row);
        if (WinError.ERROR_ACCESS_DENIED == code) {
            // throw new IllegalStateException("ACCESS_DENIED")
            throw new Win32Exception(code);
        }
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    public static void delete(final InetAddress dst, final byte prefix, final InetAddress gw, final long ifaceLuid) {
        final MIB_IPFORWARD_ROW2 row = createMibIpForwardRow2(dst, prefix, null, ifaceLuid);
        final int code = INSTANCE.DeleteIpForwardEntry2(row);
        if (WinError.ERROR_ACCESS_DENIED == code) {
            // throw new IllegalStateException("ACCESS_DENIED")
            throw new Win32Exception(code);
        }
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    public static void get(final InetAddress dst, final byte prefix, final InetAddress gw, final long ifaceLuid) {
        final MIB_IPFORWARD_ROW2 row = createMibIpForwardRow2(dst, prefix, null, 0);
        final int code = INSTANCE.GetIpForwardEntry2(row);
        if (WinError.ERROR_ACCESS_DENIED == code) {
            // throw new IllegalStateException("ACCESS_DENIED")
            throw new Win32Exception(code);
        }
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    public static void main(String[] args) throws SocketException, UnknownHostException {
        final Enumeration<NetworkInterface> nix = NetworkInterface.getNetworkInterfaces();
        while (nix.hasMoreElements()) {
            NetworkInterface ni = nix.nextElement();
            System.out.println(ni.getIndex() + " -> " + ni.getName() + " -> " + ni.getDisplayName() + " -> " + ni.getParent());
        }
//        final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByAlias("以太网 2");
//        delete(InetAddress.getByName("198.18.0.0"), (byte) 24, InetAddress.getByName("10.188.70.1"), nix.luid());
    }
}
