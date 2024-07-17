package com.github.pangolin.routing.route.predicate;

import java.net.Inet4Address;

public class Subnet4RoutePredicate extends SubnetRoutePredicate<Inet4Address> {
    private final int subnetMask;
    private final int networkAddress;

    public Subnet4RoutePredicate(final Inet4Address ipAddress, final int cidrPrefix) {
        super(ipAddress, checkPrefix(cidrPrefix));
        this.subnetMask = prefixToSubnetMask(cidrPrefix);
        this.networkAddress = ipAddressToInt(ipAddress);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(final Inet4Address address) {
        final int ipAddress = ipAddressToInt(address);
        return (ipAddress & subnetMask) == networkAddress;
    }

    private int prefixToSubnetMask(final int cidrPrefix) {
        /*-
         * Perform the shift on a long and downcast it to int afterwards.
         * This is necessary to handle a cidrPrefix of zero correctly.
         * The left shift operator on an int only uses the five least
         * significant bits of the right-hand operand. Thus -1 << 32 evaluates
         * to -1 instead of 0. The left shift operator applied on a long
         * uses the six least significant bits.
         *
         * Also see https://github.com/netty/netty/issues/2767
         */
        return (int) ((-1L << 32 - cidrPrefix) & 0xffffffff);
    }

    private static int ipAddressToInt(final Inet4Address ipAddress) {
        final byte[] ipBytes = ipAddress.getAddress();
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    static int checkPrefix(final int cidrPrefix) {
        if (0 > cidrPrefix || cidrPrefix > 32) {
            throw new IllegalArgumentException(String.format("IPv4 requires the subnet prefix to be in range of [0,32]. The prefix was: %d", cidrPrefix));
        }
        return cidrPrefix;
    }

}