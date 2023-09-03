package com.github.pangolin.proxy;

import io.netty.channel.ChannelHandler;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class RoutingProxySelector implements ProxySelector {

    @Override
    public ChannelHandler newProxyHandler(final SocketAddress destination) {
        if (destination instanceof InetSocketAddress) {
            final InetSocketAddress address = (InetSocketAddress) destination;
            if (address.isUnresolved()) {
                final String domain = address.getHostString();
            } else {
                final String hostAddress = address.getAddress().getHostAddress();
                if (NetUtil.isValidIpV4Address(hostAddress)) {

                } if (NetUtil.isValidIpV6Address(hostAddress)) {
                }
            }
        }
        return null;
    }

}