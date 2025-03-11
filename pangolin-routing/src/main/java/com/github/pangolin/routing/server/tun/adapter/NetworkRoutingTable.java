package com.github.pangolin.routing.server.tun.adapter;

import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinNetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.linux.LinuxNetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkRoutingTable;
import com.sun.jna.Platform;

import java.net.InetAddress;

/**
 *
 */
public abstract class NetworkRoutingTable {
    public static class Route {
        private final InetAddress address;
        private final int prefix;
        private final InetAddress gateway;
        private final int metric;

        public Route(final InetAddress address, final int prefix,
                     final InetAddress gateway, final int metric) {
            this.address = address;
            this.prefix = prefix;
            this.gateway = gateway;
            this.metric = metric;
        }
    }

    /**
     * Add a new route.
     *
     * @param dst    the destination network or host
     * @param prefix the netmask prefix length
     * @param gw     the gateway used for route packets, the specified gateway must be reachable first.
     * @param metric the metric field in the routing table (used by routing daemons)
     * @param ifname the interface name, force the route to be associated with the specified device
     */
    public abstract void add(final InetAddress dst, final byte prefix,
                             final InetAddress gw, final int metric, final String ifname);

    /**
     * Add a new route.
     *
     * @param dst     the destination network or host
     * @param prefix  the netmask prefix length
     * @param gw      the gateway used for route packets, the specified gateway must be reachable first.
     * @param metric  the metric field in the routing table (used by routing daemons)
     * @param ifindex the interface index, force the route to be associated with the specified device
     */
    public abstract void add(final InetAddress dst, final byte prefix,
                             final InetAddress gw, final int metric, final int ifindex);

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
