package com.github.pangolin.routing.route.predicate;

import io.netty.util.NetUtil;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class Subnet6RoutePredicateFactory implements RoutePredicateFactory<Inet6Address, String> {
    private static final String DIV = "/";

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "IP-CIDR6";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RoutePredicate<Inet6Address> apply(final String definition) {
        final int index = definition.indexOf(DIV);
        final String address = 0 < index ? definition.substring(0, index) : definition;

        final Inet6Address ipAddress = checkIpAddress(address);
        int cidrPrefix;
        if (0 < index && index < definition.length() - 1) {
            cidrPrefix = checkPrefix(definition.substring(index + 1));
        } else {
            cidrPrefix = ipAddress.getAddress().length * Byte.SIZE;
        }
        return new Subnet6RoutePredicate(ipAddress, cidrPrefix);
    }

    private static Inet6Address checkIpAddress(final String ipAddress) {
        if (!NetUtil.isValidIpV6Address(ipAddress)) {
            throw new IllegalArgumentException("Only IPv6 addresses are supported. The ip address was: " + ipAddress);
        }
        final byte[] ipBytes = NetUtil.createByteArrayFromIpAddressString(ipAddress);
        if (null == ipBytes) {
            throw new IllegalArgumentException("Only IPv6 addresses are supported. The ip address was: " + ipAddress);
        }
        try {
            return (Inet6Address) InetAddress.getByAddress(ipBytes);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException("ipAddress", e);
        }
    }

    private static int checkPrefix(final String cidrPrefix) {
        return Subnet6RoutePredicate.checkPrefix(Integer.parseInt(cidrPrefix));
    }

}