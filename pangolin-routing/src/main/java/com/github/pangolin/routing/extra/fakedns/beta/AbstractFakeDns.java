package com.github.pangolin.routing.extra.fakedns.beta;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Fake DNS.
 * @param <T>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3089">Fake IP</a>
 */
public abstract class AbstractFakeDns<T> {
    protected final int leaseTime;

    private final Cache<String, T> leases;
    private final Map<T, String> hostnames;

    private final SimplePool<T> idles;

    public AbstractFakeDns(final int leaseTime) {
        this.leaseTime = leaseTime;
        this.leases = createLeases(leaseTime);
        this.hostnames = Maps.newConcurrentMap();
        this.idles = new SimplePool<T>(createGenerator());
    }

    protected abstract Generator<T> createGenerator();

    private Cache<String, T> createLeases(final int leaseTime) {
        return CacheBuilder.newBuilder()
                .recordStats()
                .expireAfterAccess(leaseTime, TimeUnit.SECONDS)
                .removalListener((RemovalListener<String, T>) n -> this.doRelease(n.getKey(), n.getValue()))
                .build();
    }


    private void doRelease(final String hostname, final T address) {
        hostnames.remove(address);
        idles.release(address);
    }

    private T doAcquire(final String hostname) {
        final T address = idles.acquire();
        hostnames.put(address, hostname);
        return address;
    }

    protected T doResolve(final String hostname) {
        try {
            return leases.get(hostname, () -> doAcquire(hostname));
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    protected String doResolve(final T address) {
        final String hostname = hostnames.get(address);
        if (null != hostname) {
            leases.getIfPresent(hostname);
        }
        return hostname;
    }

    public abstract boolean isFakeAddress(final byte[] address);


    private class SimplePool<T> {
        private final Generator<T> factory;
        private final ConcurrentLinkedQueue<T> idle;

        private SimplePool(final Generator<T> factory) {
            this.factory = factory;
            this.idle = new ConcurrentLinkedQueue<>();
        }

        synchronized T acquire() {
            final T address = idle.poll();
            if (null != address) {
                return address;
            }
            return factory.next();
        }

        void release(T address) {
            idle.offer(address);
        }

    }
}