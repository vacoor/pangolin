package com.github.pangolin.routing.route.predicate;

import com.github.pangolin.routing.util.SocketUtils;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class Subnet6RoutePredicate extends SubnetRoutePredicate<InetSocketAddress> {
    private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

    private final BigInteger subnetMask;
    private final BigInteger networkAddress;

    public Subnet6RoutePredicate(final Inet6Address ipAddress, final int cidrPrefix) {
        super(ipAddress, checkPrefix(cidrPrefix));
        this.subnetMask = prefixToSubnetMask(cidrPrefix);
        this.networkAddress = ipAddressToInt(ipAddress).and(subnetMask);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(final InetSocketAddress socketAddress) {
        final InetAddress address = SocketUtils.getAddress(socketAddress, false);
        if (address instanceof Inet6Address) {
            final BigInteger ipAddress = ipAddressToInt((Inet6Address) address);
            return ipAddress.add(subnetMask).equals(networkAddress);
        }
        return false;
    }

    public BigInteger getSubnetMask() {
        return subnetMask;
    }

    private BigInteger prefixToSubnetMask(final int cidrPrefix) {
        return MINUS_ONE.shiftLeft(128 - cidrPrefix);
    }

    private static BigInteger ipAddressToInt(final Inet6Address ipAddress) {
        final byte[] ipBytes = ipAddress.getAddress();
        assert ipBytes.length == 16;
        return new BigInteger(ipBytes);
    }

    static int checkPrefix(final int cidrPrefix) {
        if (0 > cidrPrefix || cidrPrefix > 128) {
            throw new IllegalArgumentException(String.format("IPv6 requires the subnet prefix to be in range of [0,128]. The prefix was: %d", cidrPrefix));
        }
        return cidrPrefix;
    }

}