package com.github.pangolin.routing.beta.tun.fakedns.beta;

import com.google.common.cache.*;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public abstract class AbstractAddressAllocator2<T extends InetAddress> {
    private final int maxLeaseTime;

    protected AbstractAddressAllocator2(final int maxLeaseTime) {
        this.maxLeaseTime = maxLeaseTime;
        this.leases = CacheBuilder.newBuilder()
                        .recordStats()
                        .expireAfterAccess(maxLeaseTime, TimeUnit.SECONDS)
                        .removalListener(new RemovalListener<String, T>() {
                            @Override
                            public void onRemoval(final RemovalNotification<String, T> n) {
                                release(n.getKey(), n.getValue());
                            }
                        })
                        .build(new CacheLoader<String, T>() {
                            @Override
                            public T load(final String s) throws Exception {
                                return acquire0(s);
                            }
                        });
    }


    private final Cache<String, T> leases;
    private final ConcurrentLinkedQueue<T> expired = new ConcurrentLinkedQueue<>();

    private void release(final String s, final T lease) {
        expired.offer(lease);
    }

    public T acquire(final String key) {
        try {
            return leases.get(key, () -> acquire0(key));
        } catch (ExecutionException e) {
            return null;
        }
    }

    private T acquire0(final String key) {
        final T lease = expired.poll();
        if (null != lease) {
            return lease;
        }
        return acquire0();
    }

    public abstract T acquire0();

}