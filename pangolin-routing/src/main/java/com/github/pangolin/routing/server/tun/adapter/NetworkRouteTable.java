package com.github.pangolin.routing.server.tun.adapter;

import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinNetworkRouteTable;
import com.github.pangolin.routing.server.tun.adapter.linux.LinuxNetworkRouteTable;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkRouteTable;
import com.sun.jna.Platform;

import java.net.InetAddress;

/**
 *
 */
public class NetworkRouteTable {
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

    public static NetworkRouteTable get() {
        if (Platform.isLinux()) {
            return LinuxNetworkRouteTable.get();
        }
        if (Platform.isMac()) {
            return DarwinNetworkRouteTable.get();
        }
        if (Platform.isWindows()) {
            return WindowsNetworkRouteTable.get();
        }
        throw new UnsupportedOperationException();
    }

}
