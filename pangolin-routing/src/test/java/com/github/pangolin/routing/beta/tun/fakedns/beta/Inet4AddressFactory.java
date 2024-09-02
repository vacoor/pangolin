package com.github.pangolin.routing.beta.tun.fakedns.beta;

import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

public class Inet4AddressFactory implements InetAddressFactory<Inet4Address> {

    private final int subnetMask;
    private final int networkAddress;

    private final int min;
    private final int max;
    private final AtomicInteger current;

    public Inet4AddressFactory(final Inet4Address ipAddress, final int cidrPrefix) {
        this(ipAddress, cidrPrefix, null, null);
    }

    public Inet4AddressFactory(final Inet4Address ipAddress, final int cidrPrefix,
                               final Inet4Address startAddress, final Inet4Address endAddress) {
        this.subnetMask = prefixToSubnetMask(cidrPrefix);
        this.networkAddress = ipAddressToInt(ipAddress) & subnetMask;

        final int lowerBound = networkAddress + 1;
        // excludes network address & multicast address
        final int upperBound = networkAddress + (0xFFFFFFFF - subnetMask) - 1 - 1;

        this.min = null != startAddress ? checkInSubnet(ipAddressToInt(startAddress), lowerBound, upperBound) : lowerBound;
        this.max = null != endAddress ? checkInSubnet(ipAddressToInt(endAddress), lowerBound, upperBound) : upperBound;
        this.current = new AtomicInteger(min);
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

    private static int checkPrefix(final int cidrPrefix) {
        if (0 > cidrPrefix || cidrPrefix > 32) {
            throw new IllegalArgumentException(String.format("IPv4 requires the subnet prefix to be in range of [0,32]. The prefix was: %d", cidrPrefix));
        }
        return cidrPrefix;
    }

    private static int checkInSubnet(final int address, final int lowerBound, final int upperBound) {
        if (lowerBound <= address && address <= upperBound) {
            return address;
        }
        throw new IllegalArgumentException(String.format("address %s not in subnet", address));
    }

    @Override
    public Inet4Address create() {
        int value;
        do {
            value = current.get();
            if (value > max) {
                return null;
            }
        } while (!current.compareAndSet(value, value + 1));

        try {
            return (Inet4Address) InetAddress.getByAddress(new byte[]{
                    (byte) (value >> 24 & 0xff),
                    (byte) (value >> 16 & 0xff),
                    (byte) (value >> 8 & 0xff),
                    (byte) (value & 0xff)
            });
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Inet4AddressFactory create(final String definition) {
        final int index = definition.indexOf("/");
        final String address = 0 < index ? definition.substring(0, index) : definition;

        final Inet4Address ipAddress = checkIpAddress(address);
        int cidrPrefix;
        if (0 < index && index < definition.length() - 1) {
            cidrPrefix = checkPrefix(Integer.parseInt(definition.substring(index + 1)));
        } else {
            cidrPrefix = ipAddress.getAddress().length * Byte.SIZE;
        }
        return new Inet4AddressFactory(ipAddress, cidrPrefix);
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
}