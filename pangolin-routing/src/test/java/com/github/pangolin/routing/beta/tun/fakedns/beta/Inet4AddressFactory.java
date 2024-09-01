package com.github.pangolin.routing.beta.tun.fakedns.beta;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

public class Inet4AddressFactory implements InetAddressFactory<Inet4Address> {

    private final int min;
    private final int max;
    private final AtomicInteger current;

    public Inet4AddressFactory(final int min, final int max) {
        this.min = min;
        this.max = max;
        this.current = new AtomicInteger(min);
    }

    @Override
    public Inet4Address create() {
        int value;
        do {
            value = current.get();
            if (value > max) {
                return null;
            }
        } while(!current.compareAndSet(value, value + 1));

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

}