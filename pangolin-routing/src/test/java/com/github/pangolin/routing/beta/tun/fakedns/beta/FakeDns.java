package com.github.pangolin.routing.beta.tun.fakedns.beta;

import com.google.common.cache.*;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class FakeDns {
    private final int maxLeaseTime;
    private final Cache<String, Inet4Address> leases;
    private final InetAddressPool<Inet4Address> pool;

    protected FakeDns(final int maxLeaseTime, final InetAddressPool<Inet4Address> pool) {
        this.maxLeaseTime = maxLeaseTime;
        this.leases = CacheBuilder.newBuilder()
                        .recordStats()
                        .expireAfterAccess(maxLeaseTime, TimeUnit.SECONDS)
                        .removalListener(new RemovalListener<String, Inet4Address>() {
                            @Override
                            public void onRemoval(final RemovalNotification<String, Inet4Address> n) {
                                release(n.getKey(), n.getValue());
                            }
                        })
                        .build();
        this.pool = pool;
    }



    private void release(final String s, final Inet4Address lease) {
        pool.release(lease);
    }

    public Inet4Address acquire(final String key) {
        try {
            return leases.get(key, pool::acquire);
        } catch (ExecutionException e) {
            return null;
        }
    }

    private static int ipAddressToInt(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    public static void main(String[] args) throws InterruptedException {
        final int min = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.1"));
        final int max = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.254"));
        final InetAddressPool<Inet4Address> pool = new InetAddressPool<>(new Inet4AddressFactory(min, max));
        FakeDns fakeDns = new FakeDns(1, pool);

        for (int i = 0; ; i++) {
            final String key = "baidu" + i + ".com";
            Inet4Address addr = fakeDns.acquire(key);
            System.out.println(key + " -> " + addr);
            TimeUnit.MILLISECONDS.sleep(1500);
        }
    }
}