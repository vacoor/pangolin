package com.github.pangolin.routing.acceptor.tun.fakedns.beta;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.netty.util.NetUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Fake DNS.
 * @param <T>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3089">Fake IP</a>
 */
public abstract class AbstractFakeDns<T extends InetAddress> {
    protected final int leaseTime;

    private final Cache<String, T> leases;
    private final Map<T, String> hostnames;

    private final SimplePool<T> idles;

    public AbstractFakeDns(final int leaseTime) {
        this.leaseTime = leaseTime;
        this.leases = createLeases(leaseTime);
        this.hostnames = Maps.newConcurrentMap();
        this.idles = new SimplePool<T>(createGenerator());
//        init();
    }

    protected abstract Generator<T> createGenerator();

    private Cache<String, T> createLeases(final int leaseTime) {
        return CacheBuilder.newBuilder()
                .recordStats()
                .expireAfterAccess(leaseTime, TimeUnit.SECONDS)
                .removalListener((RemovalListener<String, T>) n -> this.doRelease(n.getKey(), n.getValue()))
                .build();
    }

    public void init() {
        final Map<String, T> addresses = read();
        for (Map.Entry<String, T> entry : addresses.entrySet()) {
            doPut(entry.getKey(), entry.getValue());
        }
    }

    private void doPut(final String hostname, final T address) {
        leases.put(hostname, address);
        hostnames.put(address, hostname);
    }

    protected Map<String, T> read() {
        try {
            final Map<String, T> ret = Maps.newTreeMap();
            final Path path = Paths.get("./fakedns.host.txt");
            if (Files.isReadable(path)) {
                final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (final String line : lines) {
                    final String[] m = line.split("\\s+", 2);
                    final String hostname = m[0];
                    if (NetUtil.isValidIpV4Address(m[1])) {
                        T addr = (T) InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(m[1]));
                        ret.put(hostname, addr);
                    }
                }
            }
            return ret;
        } catch (final IOException io) {
            throw new IllegalStateException(io);
        }
    }

    protected void write(final String hostname, final T address) {
        final String line = hostname + " " + address.getHostAddress();
        final Path path = Paths.get("./fakedns.host.txt");
        try {
            Files.write(
                    path, Collections.singleton(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void remove(final String hostnameToRemove) {
        try {
            final Path path = Paths.get("./fakedns.host.txt");
            final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            final List<String> newLines = Lists.newLinkedList();
            for (final String line : lines) {
                final String[] m = line.split("\\s+", 2);
                if (m.length < 2) {
                    continue;
                }
                final String hostname = m[0];
                if (!hostname.equalsIgnoreCase(hostnameToRemove)) {
                    newLines.add(line);
                }
            }
            Files.write(
                    path, newLines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (final IOException io) {
            throw new IllegalStateException(io);
        }
    }

    private void doRelease(final String hostname, final T address) {
        hostnames.remove(address);
        idles.release(address);
//        remove(hostname);
    }

    private T doAcquire(final String hostname) {
        T address;
        do {
            address = idles.acquire();
            if (null != address && null == hostnames.putIfAbsent(address, normalize(hostname))) {
                // write to file
//                write(hostname, address);
                return address;
            }
        } while (null != address);
        return address;
    }

    private String normalize(final String hostname) {
        return '.' == hostname.charAt(hostname.length() - 1)
            ? hostname.substring(0, hostname.length() - 1)
            : hostname;
    }

    public T doResolve(final String hostname) {
        try {
            final String hostnameToUse = normalize(hostname);
            return leases.get(hostnameToUse, () -> doAcquire(hostnameToUse));
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    public String doResolve(final T address) {
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

        T acquire() {
            // ConcurrentLinkedQueue.poll() is already thread-safe.
            // factory.next() uses AtomicInteger/AtomicReference CAS internally and needs no external lock.
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