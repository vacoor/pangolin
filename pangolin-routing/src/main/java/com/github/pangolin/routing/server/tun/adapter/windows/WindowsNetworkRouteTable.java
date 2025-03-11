package com.github.pangolin.routing.server.tun.adapter.windows;

import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterface.interfaceIndexToLuid;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterface.interfaceNameToLuid;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsUtils.toInetAddress;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsUtils.writeSockAddr;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.MIB_IPFORWARD_ROW2;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.MIB_IPPROTO_NETMGMT;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.*;

import com.github.pangolin.routing.server.tun.adapter.NetworkRouteTable;
import com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.PointerByReference;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 *
 */
public class WindowsNetworkRouteTable extends NetworkRouteTable {
    private static final IpHlpLib IP_HLP_API = IpHlpLib.INSTANCE;

    private static final WindowsNetworkRouteTable INSTANCE = new WindowsNetworkRouteTable();

    private WindowsNetworkRouteTable() {
    }

    public void add(final InetAddress destination, final byte prefix,
                    final InetAddress gateway, final int metric, final String ifname) {
        add0(destination, prefix, gateway, metric, null != ifname ? interfaceNameToLuid(ifname) : 0);
    }

    public void add(final InetAddress destination, final byte prefix,
                    final InetAddress gateway, final int metric, final int ifindex) {
        add0(destination, prefix, gateway, metric, 0 < ifindex ? interfaceIndexToLuid(ifindex) : 0);
    }

    public void delete(final InetAddress destination, final byte prefix, final String ifname) {
        delete0(destination, prefix, null != ifname ? interfaceNameToLuid(ifname) : 0);
    }

    public void delete(final InetAddress destination, final byte prefix, final int ifindex) {
        delete0(destination, prefix, 0 < ifindex ? interfaceIndexToLuid(ifindex) : 0);
    }

    public static WindowsNetworkRouteTable get() {
        return INSTANCE;
    }


    public static void add0(final InetAddress destination, final byte prefix,
                            final InetAddress gateway, final int metric, final long interfaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = interfaceLuid;
        row.Loopback = new WinDef.BOOL(0);
        row.ValidLifetime = 0xFFFFFFFF;
        row.PreferredLifetime = 0xFFFFFFFF;
        row.Metric = metric;

        writeSockAddr(row.DestinationPrefix.Prefix, destination);
        row.DestinationPrefix.PrefixLength = prefix;

        writeSockAddr(row.NextHop, gateway);

        final int code = IP_HLP_API.CreateIpForwardEntry2(row);
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    public static void delete0(final InetAddress destination, final byte prefix, final long interfaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = interfaceLuid;

        writeSockAddr(row.DestinationPrefix.Prefix, destination);
        row.DestinationPrefix.PrefixLength = prefix;

        final int code = IP_HLP_API.DeleteIpForwardEntry2(row);
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    public static Route get(final InetAddress destination, final byte prefix) {
        final MIB_IPFORWARD_ROW2 row = get0(destination, prefix);
        return new Route(
                toInetAddress(row.DestinationPrefix.Prefix),
                row.DestinationPrefix.PrefixLength,
                toInetAddress(row.NextHop),
                row.Metric
        );
    }

    private static MIB_IPFORWARD_ROW2 get0(final InetAddress destination, final byte prefix) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        writeSockAddr(row.DestinationPrefix.Prefix, destination);
        row.DestinationPrefix.PrefixLength = prefix;
        // row.InterfaceLuid = interfaceLuid;

        final int code = IP_HLP_API.GetIpForwardEntry2(row);
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
        final WindowsNetworkInterface nix = WindowsNetworkInterface.getByAlias("以太网 2");
        nix.getInterfaceAddresses();
        final long luid = nix.luid();

//        InetAddress dst = InetAddress.getByName("198.18.0.0");
//        int prefix = (byte) 24;

//        NetworkRouteTable rt = WindowsNetworkRouteTable.get();
        final PointerByReference pTableRef = new PointerByReference();
        final int code = IP_HLP_API.GetIpForwardTable2(AF_UNSPEC, pTableRef);
        // something wrong
        if (code != WinError.NO_ERROR && code != WinError.ERROR_NOT_FOUND) {
            throw new RuntimeException("Failed to list route table:" + code);
        }

        final MIB_IPFORWARD_TABLE2 table = new MIB_IPFORWARD_TABLE2(pTableRef.getValue());
        assert table.NumEntries == table.Table.length;

//        Pointer ptr = table.Table.getPointer();
        for (int i = 0; i < table.NumEntries; i++) {
            final MIB_IPFORWARD_ROW2 row = table.Table[i];;
//            if (AF_INET == row.DestinationPrefix.Prefix.si_family || AF_INET6 == row.DestinationPrefix.Prefix.si_family) {
                System.out.println(toInetAddress(row.DestinationPrefix.Prefix) + "/" + row.DestinationPrefix.PrefixLength + " -> " + toInetAddress(row.NextHop) + " via " + row.InterfaceIndex);
//            }
        }
        IP_HLP_API.FreeMibTable(table.getPointer());

//        InetAddress gw = InetAddress.getByName("10.188.70.1");
//        InetAddress nextHop = get(dst, (byte) prefix);

//         add(dst, (byte) prefix, gw, luid);
//        get(dst, (byte) prefix);
//        delete(dst, (byte) prefix, luid);
    }
}
