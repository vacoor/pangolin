package com.github.pangolin.routing.rule.pattern;

import io.netty.util.NetUtil;
import io.netty.util.internal.SocketUtils;

import java.math.BigInteger;
import java.net.*;

/**
 *
 */
public class SubnetPattern implements DestinationPattern {
    private final InetAddress ipAddress;
    private final int prefixLength;
    private final DestinationPattern delegate;
    private volatile InetAddress networkAddress;

    public SubnetPattern(final String ipAddress, final int cidrPrefix) {
        try {
            final InetAddress inetAddress = SocketUtils.addressByName(ipAddress);
            this.ipAddress = inetAddress;
            this.prefixLength = cidrPrefix;
            if (inetAddress instanceof Inet4Address) {
                delegate = new Inet4SubnetPattern(ipAddress + "/" + cidrPrefix, (Inet4Address) inetAddress, cidrPrefix);
            } else if (inetAddress instanceof Inet6Address) {
                delegate = new Inet6SubnetPattern(ipAddress + "/" + cidrPrefix, (Inet6Address) inetAddress, cidrPrefix);
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

    /**
     * @return The first address in the network.
     */
    public InetAddress getNetworkAddress() {
        if (null != networkAddress) {
            return networkAddress;
        }
        return networkAddress = getNetworkAddress(ipAddress, prefixLength);
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public DestinationPattern getDelegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return getNetworkAddress().getHostAddress() + "/" + prefixLength;
    }

    private static InetAddress getNetworkAddress(final InetAddress ipAddress, final int prefixLength) {
        byte[] ipBytes = ipAddress.getAddress();
        byte[] networkBytes = new byte[ipBytes.length];
        int curPrefix = prefixLength;
        for (int i = 0; i < ipBytes.length && curPrefix > 0; i++) {
            byte b = ipBytes[i];
            if (curPrefix < 8) {
                int shiftN = 8 - curPrefix;
                b = (byte) ((b >> shiftN) << shiftN);
            }
            networkBytes[i] = b;
            curPrefix -= 8;
        }

        try {
            return InetAddress.getByAddress(networkBytes);
        } catch (UnknownHostException e) {
            throw new RuntimeException("Illegal network address byte length of " + networkBytes.length);
        }
    }

    public static class Inet4SubnetPattern implements DestinationPattern {
        private final String name;
        private final int subnetMask;
        private final int networkAddress;

        Inet4SubnetPattern(final String name, final Inet4Address ipAddress, final int cidrPrefix) {
            if (cidrPrefix < 0 || cidrPrefix > 32) {
                throw new IllegalArgumentException(String.format("IPv4 requires the subnet prefix to be in range of [0,32]. The prefix was: %d", cidrPrefix));
            }

            this.name = name;
            subnetMask = prefixToSubnetMask(cidrPrefix);
            networkAddress = ipToInt(ipAddress) & subnetMask;
        }

        @Override
        public boolean matches(final InetSocketAddress destination) {
            try {
                InetAddress inetAddress = null;
                if (destination.isUnresolved()) {
                    if (NetUtil.isValidIpV4Address(destination.getHostString())) {
                        inetAddress = InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(destination.getHostString()));
                    }
                } else {
                    inetAddress = destination.getAddress();
                }
                if (inetAddress instanceof Inet4Address) {
                    final int ipAddress = ipToInt((Inet4Address) inetAddress);
                    return (ipAddress & subnetMask) == networkAddress;
                }
                return false;
            } catch (UnknownHostException e) {
                return false;
            }
        }

        public String getNetworkAddress() {
            return stringify(networkAddress);
        }

        public String getSubnetMask() {
            return stringify(subnetMask);
        }

        private String stringify(final int ip) {
            return (ip >> 24 & 0xff) + "." + (ip >> 16 & 0xff) + "." + (ip >> 8 & 0xff) + "." + (ip & 0xff);
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

    public static class Inet6SubnetPattern implements DestinationPattern {
        private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

        private final String name;
        private final BigInteger networkAddress;
        private final BigInteger subnetMask;

        Inet6SubnetPattern(final String name, final Inet6Address ipAddress, int cidrPrefix) {
            if (cidrPrefix < 0 || cidrPrefix > 128) {
                throw new IllegalArgumentException(String.format("IPv6 requires the subnet prefix to be in range of " +
                        "[0,128]. The prefix was: %d", cidrPrefix));
            }

            this.name = name;
            subnetMask = prefixToSubnetMask(cidrPrefix);
            networkAddress = ipToInt(ipAddress).and(subnetMask);
        }

        @Override
        public boolean matches(final InetSocketAddress destination) {
            InetAddress inetAddress = null;
            if (destination.isUnresolved()) {
                if (NetUtil.isValidIpV6Address(destination.getHostString())) {
//                        byte[] bytes = AddressUtils.textToNumericFormatV6(destination.getHostString());
//                        inetAddress = null != bytes ? InetAddress.getByAddress(bytes) : null;
                    inetAddress = NetUtil.getByName(destination.getHostString());
                }
            } else {
                inetAddress = destination.getAddress();
            }
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
