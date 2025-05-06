package com.github.pangolin.routing.acceptor.tun.adapter;

import com.github.pangolin.routing.acceptor.tun.adapter.windows.WindowsNetworkRoutingTable;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.DarwinNetworkRoutingTable;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxNetworkRoutingTable;
import com.sun.jna.Platform;

import java.net.InetAddress;

/**
 * Manipulate the routing tables.
 */
public abstract class NetworkRoutingTable {
    public static class Route {
        private final InetAddress address;
        private final int prefix;
        private final InetAddress gateway;
        private final String ifname;
        private final int metric;

        public Route(final InetAddress address, final int prefix,
                     final InetAddress gateway, final String ifname, final int metric) {
            this.address = address;
            this.prefix = prefix;
            this.gateway = gateway;
            this.metric = metric;
            this.ifname = ifname;
        }

        @Override
        public String toString() {
            final String gw = null != gateway ? gateway.getHostAddress() : null;
            final String net = address.getHostAddress() + "/" + prefix;
            return String.format("%-40s\t%-35s\t%-10s\t%-5s", net, gw, ifname, metric);
        }
    }

    /**
     * Add a new route.
     *
     * @param dst    the destination network or host
     * @param prefix the netmask prefix length
     * @param gw     the gateway used for route packets, the specified gateway must be reachable first.
     * @param ifname the interface name, force the route to be associated with the specified device
     * @param metric the metric field in the routing table (used by routing daemons)
     */
    public abstract void add(final InetAddress dst, final byte prefix,
                             final InetAddress gw, final String ifname, final int metric);

    /**
     * Add a new route.
     *
     * @param dst     the destination network or host
     * @param prefix  the netmask prefix length
     * @param gw      the gateway used for route packets, the specified gateway must be reachable first.
     * @param ifindex the interface index, force the route to be associated with the specified device
     * @param metric  the metric field in the routing table (used by routing daemons)
     */
    public abstract void add(final InetAddress dst, final byte prefix,
                             final InetAddress gw, final int ifindex, final int metric);

    /**
     * Delete a route.
     *
     * @param dst    the destination network or host
     * @param prefix the netmask prefix length
     * @param ifname the interface name, force the route to be associated with the specified device
     */
    public abstract void delete(final InetAddress dst, final byte prefix, final String ifname);

    /**
     * Delete a route.
     *
     * @param dst     the destination network or host
     * @param prefix  the netmask prefix length
     * @param ifindex the interface index, force the route to be associated with the specified device
     */
    public abstract void delete(final InetAddress dst, final byte prefix, final int ifindex);

    public abstract Iterable<Route> routes();

    public static NetworkRoutingTable get() {
        if (Platform.isLinux()) {
            return LinuxNetworkRoutingTable.get();
        }
        if (Platform.isMac()) {
            return DarwinNetworkRoutingTable.get();
        }
        if (Platform.isWindows()) {
            return WindowsNetworkRoutingTable.get();
        }
        throw new UnsupportedOperationException();
    }

}
