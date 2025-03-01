package com.github.pangolin.routing.server.tun.adapter.util;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class NetUtils2 {

    public static <A extends InetAddress> A cidrToNetmaskAddress(final A address, final int prefix) {
        byte[] bytes = cidrPrefixToNetmask(new byte[address.getAddress().length], prefix);
        try {
            return (A) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] cidrPrefixToNetmask(final byte[] bytes, int prefix) {
        final byte[] netmask = Arrays.copyOf(bytes, bytes.length);
        Arrays.fill(netmask, (byte) 0xFF);
        netmask[prefix / Byte.SIZE] <<= prefix % Byte.SIZE;
        prefix += prefix % Byte.SIZE;
        for (int i = prefix / Byte.SIZE; i < netmask.length; i++) {
            netmask[i] = 0;
        }
        return netmask;
    }

    public static int netmaskToPrefixLength(final byte[] ipBytes) {
        int prefix_length = 0;
        for (byte b : ipBytes) {
            if ((b & 0xFF) == 0xFF) {
                prefix_length += Byte.SIZE;
                continue;
            }
            for (int j = 0; j < Byte.SIZE; j++) {
                if ((b >> j & 1) != 1) {
                    return prefix_length;
                }
                prefix_length += 1;
            }
        }
        return prefix_length;
    }

    public static InetAddress getNetworkAddress(final InetAddress ipAddress, final int prefix) {
        if (ipAddress instanceof Inet4Address) {
            final int networkAddress = ipAddress4ToInt((Inet4Address) ipAddress) & prefixToSubnetMask4(prefix);
            return toInetAddress(intToIpAddress4(networkAddress));
        } else if (ipAddress instanceof Inet6Address) {
            final Inet6Address v6 = (Inet6Address) ipAddress;
            final BigInteger netmask = prefixToSubnetMask6(prefix);
            final BigInteger networkAddress = ipAddressToInt6(v6).and(netmask);
            return toInet6Address(networkAddress.toByteArray(), v6.getScopeId());
        }
        throw new UnsupportedOperationException();
    }

    private static InetAddress toInetAddress(final byte[] addr) {
        try {
            return InetAddress.getByAddress(addr);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Inet6Address toInet6Address(final byte[] addr, final int scopeId) {
        try {
            return Inet6Address.getByAddress(null, addr, scopeId);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int prefixToSubnetMask4(final int cidrPrefix) {
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

    private static int ipAddress4ToInt(final Inet4Address ipAddress) {
        final byte[] ipBytes = ipAddress.getAddress();
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    private static byte[] intToIpAddress4(final int i) {
        return new byte[]{
                (byte) (i >> 24 & 0xff),
                (byte) (i >> 16 & 0xff),
                (byte) (i >> 8 & 0xff),
                (byte) (i & 0xff)
        };
    }

    private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

    private static BigInteger prefixToSubnetMask6(final int cidrPrefix) {
        return MINUS_ONE.shiftLeft(128 - cidrPrefix);
    }

    private static BigInteger ipAddressToInt6(final Inet6Address ipAddress) {
        final byte[] ipBytes = ipAddress.getAddress();
        assert ipBytes.length == 16;
        return new BigInteger(ipBytes);
    }

    public static void main(String[] args) throws UnknownHostException {
        System.out.println(getNetworkAddress(InetAddress.getByName("198.18.0.1"), 24));
    }
}