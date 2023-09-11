package com.github.pangolin.routing.pattern;

import io.netty.util.internal.SocketUtils;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 *
 */
public class InetSubnetPattern implements DestinationPattern {
    private final DestinationPattern delegate;

    public InetSubnetPattern(final String ipAddress, final int cidrPrefix) {
        try {
            final InetAddress inetAddress = SocketUtils.addressByName(ipAddress);
            if (inetAddress instanceof Inet4Address) {
                delegate = new Inet4SubnetPattern((Inet4Address) inetAddress, cidrPrefix);
            } else if (inetAddress instanceof Inet6Address) {
                delegate = new Inet6SubnetPattern((Inet6Address) inetAddress, cidrPrefix);
            } else {
                throw new IllegalArgumentException("Only IPv4 and IPv6 addresses are supported");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("ipAddress", e);
        }
    }

    @Override
    public boolean matches(final InetSocketAddress destination) {
        return delegate.matches(destination);
    }

    private static class Inet4SubnetPattern implements DestinationPattern {
        private final int networkAddress;
        private final int subnetMask;

        Inet4SubnetPattern(final Inet4Address ipAddress, final int cidrPrefix) {
            if (cidrPrefix < 0 || cidrPrefix > 32) {
                throw new IllegalArgumentException(String.format("IPv4 requires the subnet prefix to be in range of [0,32]. The prefix was: %d", cidrPrefix));
            }

            subnetMask = prefixToSubnetMask(cidrPrefix);
            networkAddress = ipToInt(ipAddress) & subnetMask;
        }

        @Override
        public boolean matches(final InetSocketAddress destination) {
            final InetAddress inetAddress = destination.getAddress();
            if (inetAddress instanceof Inet4Address) {
                final int ipAddress = ipToInt((Inet4Address) inetAddress);
                return (ipAddress & subnetMask) == networkAddress;
            }
            return false;
        }

        private static int ipToInt(final Inet4Address ipAddress) {
            final byte[] octets = ipAddress.getAddress();
            assert octets.length == 4;
            return (octets[0] & 0xff) << 24 | (octets[1] & 0xff) << 16 | (octets[2] & 0xff) << 8 | octets[3] & 0xff;
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

    private static class Inet6SubnetPattern implements DestinationPattern {
        private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

        private final BigInteger networkAddress;
        private final BigInteger subnetMask;

        Inet6SubnetPattern(final Inet6Address ipAddress, int cidrPrefix) {
            if (cidrPrefix < 0 || cidrPrefix > 128) {
                throw new IllegalArgumentException(String.format("IPv6 requires the subnet prefix to be in range of " +
                        "[0,128]. The prefix was: %d", cidrPrefix));
            }

            subnetMask = prefixToSubnetMask(cidrPrefix);
            networkAddress = ipToInt(ipAddress).and(subnetMask);
        }

        @Override
        public boolean matches(final InetSocketAddress destination) {
            final InetAddress inetAddress = destination.getAddress();
            if (inetAddress instanceof Inet6Address) {
                final BigInteger ipAddress = ipToInt((Inet6Address) inetAddress);
                return ipAddress.and(subnetMask).equals(networkAddress);
            }
            return false;
        }

        private static BigInteger ipToInt(Inet6Address ipAddress) {
            final byte[] octets = ipAddress.getAddress();
            assert octets.length == 16;
            return new BigInteger(octets);
        }

        private static BigInteger prefixToSubnetMask(int cidrPrefix) {
            return MINUS_ONE.shiftLeft(128 - cidrPrefix);
        }
    }

}
