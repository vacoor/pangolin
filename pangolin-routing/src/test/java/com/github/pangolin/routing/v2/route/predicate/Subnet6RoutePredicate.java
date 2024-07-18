package com.github.pangolin.routing.v2.route.predicate;

import java.math.BigInteger;
import java.net.Inet6Address;

public class Subnet6RoutePredicate extends SubnetRoutePredicate<Inet6Address> {
    private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

    private final BigInteger subnetMask;
    private final BigInteger networkAddress;

    public Subnet6RoutePredicate(final Inet6Address ipAddress, final int cidrPrefix) {
        super(ipAddress, checkPrefix(cidrPrefix));
        this.subnetMask = prefixToSubnetMask(cidrPrefix);
        this.networkAddress = ipAddressToInt(ipAddress);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(final Inet6Address address) {
        final BigInteger ipAddress = ipAddressToInt(address);
        return ipAddress.add(subnetMask).equals(networkAddress);
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