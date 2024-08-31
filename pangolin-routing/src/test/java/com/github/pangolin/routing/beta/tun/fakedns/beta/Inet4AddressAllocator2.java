package com.github.pangolin.routing.beta.tun.fakedns.beta;

import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Inet4AddressAllocator2 extends AbstractAddressAllocator2<Inet4Address> {

    private final int min;
    private final int max;
    private final AtomicInteger generator;

    public Inet4AddressAllocator2(final int min, final int max) {
        super(2);
        this.min = min;
        this.max = max;
        this.generator = new AtomicInteger(min);
    }

    @Override
    public Inet4Address acquire0() {
        int value;
        do {
            value = generator.get();
            if (value > max) {
                return null;
            }
        } while(!generator.compareAndSet(value, value + 1));
        try {
            return (Inet4Address) InetAddress.getByAddress(new byte[] {
                    (byte) (value >> 24 & 0xff),
                    (byte) (value >> 16 & 0xff),
                    (byte) (value >> 8 & 0xff),
                    (byte) (value & 0xff)
            });
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int ipAddressToInt(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    public static void main(String[] args) throws InterruptedException {
        final int min = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.1"));
        final int max = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.254"));
        Inet4AddressAllocator2 allocator = new Inet4AddressAllocator2(min, max);
        for (int i = 0; ; i++) {
            final String key = "baidu" + i + ".com";
            Inet4Address addr = allocator.acquire(key);
            System.out.println(key + " -> " + addr);
            TimeUnit.MILLISECONDS.sleep(1500);
        }
    }
}