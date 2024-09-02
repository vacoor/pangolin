package com.github.pangolin.routing.beta.tun.fakedns.beta;

import com.google.common.cache.*;
import com.google.common.collect.Maps;

import java.net.Inet4Address;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class FakeDns4 {
    private final int maxLeaseTime;
    private final InetAddressPool<Inet4Address> pool;
    private final Cache<String, Inet4Address> leases;
    private final Map<Inet4Address, String> addressToHostMap = Maps.newConcurrentMap();

    protected FakeDns4(final int maxLeaseTime, final InetAddressPool<Inet4Address> pool) {
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
        addressToHostMap.remove(lease);
        pool.release(lease);
    }

    public Inet4Address resolve(final String domain) {
        try {
            return leases.get(domain, new Callable<Inet4Address>() {
                @Override
                public Inet4Address call() throws Exception {
                    final Inet4Address address = pool.acquire();
                    addressToHostMap.put(address, domain);
                    return address;
                }
            });
        } catch (ExecutionException e) {
            return null;
        }
    }

    public String resolve(final Inet4Address address) {
        final String hostname = addressToHostMap.get(address);
        if (null != hostname) {
            // touch
            resolve(hostname);
        }
        return hostname;
    }

    private static int ipAddressToInt(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    public static void main(String[] args) throws InterruptedException {
//        final int min = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.1"));
//        final int max = ipAddressToInt(NetUtil.createByteArrayFromIpAddressString("198.18.0.254"));
        final String definition = "198.18.0.1/24";

        final InetAddressPool<Inet4Address> pool = new InetAddressPool<>(Inet4AddressFactory.create(definition));
        FakeDns4 fakeDns4 = new FakeDns4(1, pool);

        for (int i = 0; ; i++) {
            final String key = "baidu" + i + ".com";
            Inet4Address addr = fakeDns4.resolve(key);
            System.out.println(key + " -> " + addr);
            TimeUnit.MILLISECONDS.sleep(1500);
        }
    }
}