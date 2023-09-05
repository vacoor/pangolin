package com.github.pangolin.proxy.routing;

import io.netty.util.internal.SocketUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 *
 */
public class InetSubnetCondition implements Condition<InetSocketAddress> {
    private final Condition<InetSocketAddress> delegate;

    public InetSubnetCondition(final String ipAddress, final int cidrPrefix) {
        try {
            final InetAddress inetAddress = SocketUtils.addressByName(ipAddress);
            if (inetAddress instanceof Inet4Address) {
                delegate = new Inet4SubnetCondition((Inet4Address) inetAddress, cidrPrefix);
            } else if (inetAddress instanceof Inet6Address) {
                delegate = new Inet6SubnetCondition((Inet6Address) inetAddress, cidrPrefix);
            } else {
                throw new IllegalArgumentException("Only IPv4 and IPv6 addresses are supported");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("ipAddress", e);
        }
    }

    @Override
    public boolean matches(final InetSocketAddress inetSocketAddress) {
        return delegate.matches(inetSocketAddress);
    }

}
