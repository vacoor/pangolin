package com.github.pangolin.proxy.routing;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 *
 */
class Inet4SubnetCondition implements Condition<InetSocketAddress> {
    private final int networkAddress;
    private final int subnetMask;

    public Inet4SubnetCondition(final Inet4Address ipAddress, final int cidrPrefix) {
        if (cidrPrefix < 0 || cidrPrefix > 32) {
            throw new IllegalArgumentException(String.format("IPv4 requires the subnet prefix to be in range of " +
                    "[0,32]. The prefix was: %d", cidrPrefix));
        }

        subnetMask = prefixToSubnetMask(cidrPrefix);
        networkAddress = ipToInt(ipAddress) & subnetMask;
    }

    @Override
    public boolean matches(final InetSocketAddress address) {
        final InetAddress inetAddress = address.getAddress();
        if (inetAddress instanceof Inet4Address) {
            int ipAddress = ipToInt((Inet4Address) inetAddress);
            return (ipAddress & subnetMask) == networkAddress;
        }
        return false;
    }

    private static int ipToInt(Inet4Address ipAddress) {
        byte[] octets = ipAddress.getAddress();
        assert octets.length == 4;

        return (octets[0] & 0xff) << 24 |
                (octets[1] & 0xff) << 16 |
                (octets[2] & 0xff) << 8 |
                octets[3] & 0xff;
    }

    private static int prefixToSubnetMask(int cidrPrefix) {
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
}
