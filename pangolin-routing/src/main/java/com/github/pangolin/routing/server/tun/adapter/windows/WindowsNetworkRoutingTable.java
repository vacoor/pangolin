package com.github.pangolin.routing.server.tun.adapter.windows;

import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterface.interfaceAliasToLuid;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterface.interfaceIndexToLuid;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterface.interfaceLuidToAlias;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterface.interfaceNameToLuid;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsUtils.toInetAddress;
import static com.github.pangolin.routing.server.tun.adapter.windows.WindowsUtils.writeSockAddr;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.AF_INET;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.AF_INET6;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.MIB_IPFORWARD_ROW2;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.MIB_IPFORWARD_TABLE2;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib.MIB_IPPROTO_NETMGMT;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_UNSPEC;

import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHlpLib;
import com.google.common.collect.Lists;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;

/**
 *
 */
@Slf4j
public class WindowsNetworkRoutingTable extends NetworkRoutingTable {
    private static final IpHlpLib IP_HLP_API = IpHlpLib.INSTANCE;

    private static final WindowsNetworkRoutingTable INSTANCE = new WindowsNetworkRoutingTable();

    private WindowsNetworkRoutingTable() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final InetAddress dst, final byte prefix,
                    final InetAddress gw, final int metric, final String ifname) {
        add0(dst, prefix, gw, metric, null != ifname ? interfaceAliasToLuid(ifname) : 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final InetAddress dst, final byte prefix,
                    final InetAddress gw, final int metric, final int ifindex) {
        add0(dst, prefix, gw, metric, 0 < ifindex ? interfaceIndexToLuid(ifindex) : 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(final InetAddress dst, final byte prefix, final String ifname) {
        delete0(dst, prefix, null != ifname ? interfaceNameToLuid(ifname) : 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(final InetAddress dst, final byte prefix, final int ifindex) {
        delete0(dst, prefix, 0 < ifindex ? interfaceIndexToLuid(ifindex) : 0);
    }

    public static Route get(final InetAddress destination, final byte prefix, final String ifname) {
        final MIB_IPFORWARD_ROW2 row = get0(destination, prefix, null != ifname ? interfaceAliasToLuid(ifname) : 0);
        return null != row ? toRoute(row) : null;
    }

    public static WindowsNetworkRoutingTable get() {
        return INSTANCE;
    }

    private static void add0(final InetAddress destination, final byte prefix,
                             final InetAddress gateway, final int metric, final long interfaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = interfaceLuid;
        row.Loopback = 0;
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

    private static void delete0(final InetAddress destination, final byte prefix, final long interfaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = interfaceLuid;

        writeSockAddr(row.DestinationPrefix.Prefix, destination);
        row.DestinationPrefix.PrefixLength = prefix;

        final int code = IP_HLP_API.DeleteIpForwardEntry2(row);
        if (WinError.NO_ERROR != code && WinError.ERROR_NOT_FOUND != code) {
            throw new Win32Exception(code);
        }
    }

    private static MIB_IPFORWARD_ROW2 get0(final InetAddress destination, final byte prefix, final long interfaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        writeSockAddr(row.DestinationPrefix.Prefix, destination);
        row.DestinationPrefix.PrefixLength = prefix;
        row.InterfaceLuid = interfaceLuid;

        final int code = IP_HLP_API.GetIpForwardEntry2(row);
        if (WinError.ERROR_NOT_FOUND == code) {
            return null;
        }
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
        return row;
    }

    private static List<Route> getAll(final int family) {
        final PointerByReference pTableRef = new PointerByReference();
        final int code = IP_HLP_API.GetIpForwardTable2(family, pTableRef);
        // something wrong
        if (code != WinError.NO_ERROR && code != WinError.ERROR_NOT_FOUND) {
            throw new RuntimeException("Failed to list route table:" + code);
        }
        try {
            final Pointer pTable = pTableRef.getValue();
            final MIB_IPFORWARD_TABLE2 table = new MIB_IPFORWARD_TABLE2(pTable);
            assert table.NumEntries == table.Table.length;

            final List<Route> routes = Lists.newLinkedList();
            for (final MIB_IPFORWARD_ROW2 row : table.Table) {
                if (AF_INET == row.DestinationPrefix.Prefix.si_family
                        || AF_INET6 == row.DestinationPrefix.Prefix.si_family) {
                    /*-
                    <pre>
                    final byte netmask = row.DestinationPrefix.PrefixLength;
                    final InetAddress address = toInetAddress(row.DestinationPrefix.Prefix);
                    final InetAddress gateway = toInetAddress(row.NextHop);
                    System.out.println(String.format("%s/%s -> %s %s %s", address, netmask, gateway, row.Metric, row.InterfaceLuid));
                    </pre>
                    */
                    routes.add(toRoute(row));
                } else {
                    log.warn("SKIP unsupported family: {}, protocol: {}", row.DestinationPrefix.Prefix.si_family, row.Protocol);
                }
            }
            return routes;
        } finally {
            IP_HLP_API.FreeMibTable(pTableRef.getValue());
        }
    }

    private static Route toRoute(final MIB_IPFORWARD_ROW2 row) {
        return new Route(
                toInetAddress(row.DestinationPrefix.Prefix),
                row.DestinationPrefix.PrefixLength & 0xFF,
                toInetAddress(row.NextHop),
                interfaceLuidToAlias(row.InterfaceLuid), row.Metric
        );
    }

    public static void main(String[] args) throws SocketException, UnknownHostException {
//        final Enumeration<NetworkInterface> nix = NetworkInterface.getNetworkInterfaces();
//        while (nix.hasMoreElements()) {
//            NetworkInterface ni = nix.nextElement();
//            System.out.println(ni.getIndex() + " -> " + ni.getName() + " -> " + ni.getDisplayName() + " -> " + ni.getParent());
//        }
        final WindowsNetworkInterface nix = WindowsNetworkInterface.getByName("以太网 2");
        List<InterfaceAddressEx> interfaceAddresses = nix.getInterfaceAddresses();
        final long luid = nix.luid();


//        NetworkRoutingTable rt = WindowsNetworkRoutingTable.get();

        InetAddress dst = InetAddress.getByName("198.19.0.0");
        int prefix = (byte) 24;
        InetAddress gw = InetAddress.getByName("10.188.70.1");
        Route route = get(dst, (byte) prefix, "以太网 P");
        System.out.println(route);
        for (Route r : getAll(AF_UNSPEC)) {
            System.out.println(r);
        }

//         add(dst, (byte) prefix, gw, luid);
//        get(dst, (byte) prefix);
//        delete(dst, (byte) prefix, luid);
    }
}
