package com.github.pangolin.routing.beta.tun.fakedns.beta;

import com.github.pangolin.routing.route.predicate.Subnet6RoutePredicate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Inet6AddressFactory implements InetAddressFactory<Inet6Address> {

    private final BigInteger min;
    private final BigInteger max;
    private final AtomicReference<BigInteger> current;

    public Inet6AddressFactory(final BigInteger min, final BigInteger max) {
        this.min = min;
        this.max = max;
        this.current = new AtomicReference<>(min);
    }

    @Override
    public Inet6Address create() {
        BigInteger value;
        do {
            value = current.get();
            if (value.compareTo(max) > 0) {
                return null;
            }
        } while(!current.compareAndSet(value, value.add(BigInteger.ONE)));

        try {
            final byte[] bytes = value.toByteArray();
//            Inet6Address.getBy
            return (Inet6Address) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws UnknownHostException {
        // 2001:10::/28
        final BigInteger mask = BigInteger.valueOf(-1).shiftLeft(128 - 28);
        final Inet6Address addr = (Inet6Address) InetAddress.getByName("2001:10::");
        BigInteger subnet = new BigInteger(addr.getAddress()).and(mask);
        BigInteger min = subnet.add(BigInteger.ONE);
        BigInteger max = subnet.add(BigInteger.valueOf(100));
        Inet6AddressFactory factory = new Inet6AddressFactory(min, max);
        System.out.println(factory.create());
    }
}