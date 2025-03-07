package com.github.pangolin.routing.server.fakedns.beta;

import com.github.pangolin.routing.server.fakedns.DnsEngine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SimpleInet6FakeDns extends AbstractFakeDns<Inet6Address> {
    private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);

    private final BigInteger subnetMask;
    private final BigInteger networkAddress;

    private final BigInteger min;
    private final BigInteger max;
    private final AtomicReference<BigInteger> current;

    public SimpleInet6FakeDns(final Inet6Address ipAddress, final int cidrPrefix, final int leaseTime) {
        this(ipAddress, cidrPrefix, null, null, leaseTime);
    }

    public SimpleInet6FakeDns(final Inet6Address ipAddress, final int cidrPrefix,
                              final Inet6Address startAddress, final Inet6Address endAddress, final int leaseTime) {
        super(leaseTime);
        this.subnetMask = prefixToSubnetMask(cidrPrefix);
        this.networkAddress = ipAddressToInt(ipAddress).and(subnetMask);

        final BigInteger lowerBound = networkAddress.add(BigInteger.ONE).add(BigInteger.ONE);
        // excludes network address & multicast address
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, (byte) 0xFF);
        BigInteger x = new BigInteger(bytes);

        final BigInteger upperBound = networkAddress.add(x.subtract(subnetMask)).subtract(BigInteger.ONE).subtract(BigInteger.ONE);

        this.min = null != startAddress ? checkInSubnet(ipAddressToInt(startAddress), lowerBound, upperBound) : lowerBound;
        this.max = null != endAddress ? checkInSubnet(ipAddressToInt(endAddress), lowerBound, upperBound) : upperBound;
        this.current = new AtomicReference<>(min);
    }

    private BigInteger prefixToSubnetMask(final int cidrPrefix) {
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
        return MINUS_ONE.shiftLeft(128 - cidrPrefix);
    }

    private static BigInteger ipAddressToInt(final Inet6Address ipAddress) {
        final byte[] ipBytes = ipAddress.getAddress();
        assert ipBytes.length == 16;
        return new BigInteger(ipBytes);
    }

    public static int checkPrefix(final int cidrPrefix) {
        if (0 > cidrPrefix || cidrPrefix > 128) {
            throw new IllegalArgumentException(String.format("IPv6 requires the subnet prefix to be in range of [0,128]. The prefix was: %d", cidrPrefix));
        }
        return cidrPrefix;
    }

    private static BigInteger checkInSubnet(final BigInteger address, final BigInteger lowerBound, final BigInteger upperBound) {
        if (lowerBound.compareTo(address) <= 0 && address.compareTo(upperBound) <= 0) {
            return address;
        }
        throw new IllegalArgumentException(String.format("address %s not in subnet", address));
    }

    public Inet6Address create() {
        BigInteger value;
        do {
            value = current.get();
            if (value.compareTo(max) > 0) {
                return null;
            }
        } while (!current.compareAndSet(value, value.add(BigInteger.ONE)));
        try {
            return (Inet6Address) InetAddress.getByAddress(value.toByteArray());
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean isFakeAddress(final byte[] addr) {
        return new BigInteger(addr).and(subnetMask).compareTo(networkAddress) == 0;
    }

    public static SimpleInet6FakeDns create(final String definition, final int leaseTime) {
        final int index = definition.indexOf("/");
        final String address = 0 < index ? definition.substring(0, index) : definition;

        final Inet6Address ipAddress = checkIpAddress(address);
        int cidrPrefix;
        if (0 < index && index < definition.length() - 1) {
            cidrPrefix = checkPrefix(Integer.parseInt(definition.substring(index + 1)));
        } else {
            cidrPrefix = ipAddress.getAddress().length * Byte.SIZE;
        }
        return new SimpleInet6FakeDns(ipAddress, cidrPrefix, leaseTime);
    }

    public static Inet6Address checkIpAddress(final String ipAddress) {
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

    @Override
    protected Generator<Inet6Address> createGenerator() {
        return this::create;
    }


    public static void main(String[] args) throws InterruptedException, UnknownHostException {
//        final int min = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.1"));
//        final int max = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.254"));
        final String definition = "2001:2::/48";

        SimpleInet6FakeDns dns = SimpleInet6FakeDns.create(definition, 2);

        for (int i = 0; ; i++) {
            final String key = "baidu" + i + ".com";
            Inet6Address addr = dns.doResolve(key);
            System.out.println(key + " -> " + addr);
            TimeUnit.MILLISECONDS.sleep(1500);
        }
    }
}