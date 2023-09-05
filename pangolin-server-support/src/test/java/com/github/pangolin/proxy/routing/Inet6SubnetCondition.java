package com.github.pangolin.proxy.routing;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

class Inet6SubnetCondition implements Condition<InetSocketAddress> {
    private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

    private final BigInteger networkAddress;
    private final BigInteger subnetMask;

    public Inet6SubnetCondition(final Inet6Address ipAddress, int cidrPrefix) {
        if (cidrPrefix < 0 || cidrPrefix > 128) {
            throw new IllegalArgumentException(String.format("IPv6 requires the subnet prefix to be in range of " +
                    "[0,128]. The prefix was: %d", cidrPrefix));
        }

        subnetMask = prefixToSubnetMask(cidrPrefix);
        networkAddress = ipToInt(ipAddress).and(subnetMask);
    }

    @Override
    public boolean matches(InetSocketAddress remoteAddress) {
        final InetAddress inetAddress = remoteAddress.getAddress();
        if (inetAddress instanceof Inet6Address) {
            BigInteger ipAddress = ipToInt((Inet6Address) inetAddress);
            return ipAddress.and(subnetMask).equals(networkAddress);
        }
        return false;
    }

    private static BigInteger ipToInt(Inet6Address ipAddress) {
        byte[] octets = ipAddress.getAddress();
        assert octets.length == 16;

        return new BigInteger(octets);
    }

    private static BigInteger prefixToSubnetMask(int cidrPrefix) {
        return MINUS_ONE.shiftLeft(128 - cidrPrefix);
    }
}