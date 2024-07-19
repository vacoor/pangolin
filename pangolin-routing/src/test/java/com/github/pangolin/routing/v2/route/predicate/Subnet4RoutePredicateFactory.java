package com.github.pangolin.routing.v2.route.predicate;

import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collections;

public class Subnet4RoutePredicateFactory implements RoutePredicateFactory<InetSocketAddress, String> {
    private static final String DIV = "/";

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "IP-CIDR";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<RoutePredicate<InetSocketAddress>> apply(final String definition, final URL location) {
        final int index = definition.indexOf(DIV);
        final String address = 0 < index ? definition.substring(0, index) : definition;

        final Inet4Address ipAddress = checkIpAddress(address);
        int cidrPrefix;
        if (0 < index && index < definition.length() - 1) {
            cidrPrefix = checkPrefix(definition.substring(index + 1));
        } else {
            cidrPrefix = ipAddress.getAddress().length * Byte.SIZE;
        }
        return Collections.singletonList(new Subnet4RoutePredicate(ipAddress, cidrPrefix));
    }

    private static Inet4Address checkIpAddress(final String ipAddress) {
        if (!NetUtil.isValidIpV4Address(ipAddress)) {
            throw new IllegalArgumentException("Only IPv4 addresses are supported. The ip address was: " + ipAddress);
        }
        final byte[] ipBytes = NetUtil.createByteArrayFromIpAddressString(ipAddress);
        if (null == ipBytes) {
            throw new IllegalArgumentException("Only IPv4 addresses are supported. The ip address was: " + ipAddress);
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(ipBytes);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException("ipAddress", e);
        }
    }

    private static int checkPrefix(final String cidrPrefix) {
        return Subnet4RoutePredicate.checkPrefix(Integer.parseInt(cidrPrefix));
    }

}