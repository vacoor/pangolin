package com.github.pangolin.routing.acceptor.tun.adapter.windows;

import com.github.pangolin.routing.acceptor.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.acceptor.tun.adapter.windows.jna.IpHlpLib;
import com.google.common.collect.Lists;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.util.List;

import static com.github.pangolin.routing.acceptor.tun.adapter.windows.WindowsNetworkInterface.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.windows.WindowsUtils.toInetAddress;
import static com.github.pangolin.routing.acceptor.tun.adapter.windows.WindowsUtils.writeSockAddr;
import static com.github.pangolin.routing.acceptor.tun.adapter.windows.jna.IpHlpLib.*;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_UNSPEC;

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
                    final InetAddress gw, final String ifname, final int metric) {
        add0(dst, prefix, gw, null != ifname ? interfaceAliasToLuid(ifname) : 0, metric);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final InetAddress dst, final byte prefix,
                    final InetAddress gw, final int ifindex, final int metric) {
        add0(dst, prefix, gw, 0 < ifindex ? interfaceIndexToLuid(ifindex) : 0, metric);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(final InetAddress dst, final byte prefix, final String ifname) {
        delete0(dst, prefix, null != ifname ? interfaceAliasToLuid(ifname) : 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(final InetAddress dst, final byte prefix, final int ifindex) {
        delete0(dst, prefix, 0 < ifindex ? interfaceIndexToLuid(ifindex) : 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Route> routes() {
        return getAll0(AF_UNSPEC);
    }

    public static Route get(final InetAddress destination, final byte prefix, final String ifname) {
        final MIB_IPFORWARD_ROW2 row = get0(destination, prefix, null != ifname ? interfaceAliasToLuid(ifname) : 0);
        return null != row ? toRoute(row) : null;
    }


    public static WindowsNetworkRoutingTable get() {
        return INSTANCE;
    }

    /**
     * Add a new route.
     *
     * @param dst           the destination network or host
     * @param prefix        the netmask prefix length
     * @param gw            the gateway used for route packets, the specified gateway must be reachable first.
     * @param interfaceLuid the LUID of interface, force the route to be associated with the specified device
     * @param metric        the metric field in the routing table (used by routing daemons)
     */
    private static void add0(final InetAddress dst, final byte prefix,
                             final InetAddress gw, final long interfaceLuid, final int metric) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = interfaceLuid;
        row.Loopback = 0;
        row.ValidLifetime = 0xFFFFFFFF;
        row.PreferredLifetime = 0xFFFFFFFF;
        row.Metric = metric;

        writeSockAddr(row.DestinationPrefix.Prefix, dst);
        row.DestinationPrefix.PrefixLength = prefix;

        writeSockAddr(row.NextHop, gw);

        final int code = IP_HLP_API.CreateIpForwardEntry2(row);
        if (WinError.NO_ERROR != code) {
            throw new Win32Exception(code);
        }
    }

    /**
     * Delete a route.
     *
     * @param dst           the destination network or host
     * @param prefix        the netmask prefix length
     * @param interfaceLuid the LUID of interface, force the route to be associated with the specified device
     */
    private static void delete0(final InetAddress dst, final byte prefix, final long interfaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        row.Protocol = MIB_IPPROTO_NETMGMT;
        row.InterfaceLuid = interfaceLuid;

        writeSockAddr(row.DestinationPrefix.Prefix, dst);
        row.DestinationPrefix.PrefixLength = prefix;

        final int code = IP_HLP_API.DeleteIpForwardEntry2(row);
        if (WinError.NO_ERROR != code && WinError.ERROR_FILE_NOT_FOUND != code) {
            throw new Win32Exception(code);
        }
    }

    /**
     * Get a route.
     *
     * @param dst           the destination network or host
     * @param prefix        the netmask prefix length
     * @param interfaceLuid the LUID of interface, force the route to be associated with the specified device
     */
    private static MIB_IPFORWARD_ROW2 get0(final InetAddress dst, final byte prefix, final long interfaceLuid) {
        final MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        IP_HLP_API.InitializeIpForwardEntry(row);

        writeSockAddr(row.DestinationPrefix.Prefix, dst);
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

    /**
     * Get a list of all route.
     *
     * @param family the address family, AF_INET | AF_INET6 | AF_UNSPEC
     * @return the list of all route
     */
    private static List<Route> getAll0(final int family) {
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

}
