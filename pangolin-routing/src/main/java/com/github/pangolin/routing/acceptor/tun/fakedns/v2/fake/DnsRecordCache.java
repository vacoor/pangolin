package com.github.pangolin.routing.acceptor.tun.fakedns.v2.fake;

import com.google.common.collect.Lists;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class DnsRecordCache {
    private static final AtomicReferenceFieldUpdater<Entries, ScheduledFuture> FUTURE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(Entries.class, ScheduledFuture.class, "expirationFuture");
    private final ConcurrentMap<String, Entries> resolveCache = new ConcurrentHashMap<>();

    public List<DnsRecord> get(final String hostname) {
        final Entries entries = resolveCache.get(hostname);
        return null != entries ? entries.get() : Collections.emptyList();
    }

    public void cache(final String hostname, final DnsRecord dnsRecord, final EventLoop loop) {
        Entries entries = resolveCache.get(hostname);
        if (null == entries) {
            entries = new Entries(hostname);
            Entries old = resolveCache.putIfAbsent(hostname, entries);
            if (null != old) {
                entries = old;
            }
        }
        entries.add(dnsRecord, loop);
    }

    public boolean clear(final String hostname) {
        final Entries entries = resolveCache.remove(hostname);
        return null != entries && entries.clearAndCancel();
    }

    public void clear() {
        while (!resolveCache.isEmpty()) {
            final Iterator<Map.Entry<String, Entries>> it = resolveCache.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<String, Entries> e = it.next();
                it.remove();
                e.getValue().clearAndCancel();
            }
        }
    }


    private class Entries extends AtomicReference<List<DnsRecord>> implements Runnable {
        private final String hostname;
        // Needs to be package-private to be able to access it via the AtomicReferenceFieldUpdater
        volatile ScheduledFuture<?> expirationFuture;

        Entries(String hostname) {
            super(Collections.emptyList());
            this.hostname = hostname;
        }

        void add(final DnsRecord dnsRecord, final EventLoop loop) {
            final int ttl = (int) dnsRecord.timeToLive();
            ReferenceCountUtil.retain(dnsRecord);
            while (true) {
                final List<DnsRecord> entries = this.get();
                if (!entries.isEmpty()) {
                    final List<DnsRecord> newEntries = Lists.newArrayListWithExpectedSize(entries.size() + 1);
                    newEntries.add(dnsRecord);
                    newEntries.addAll(entries);

                    if (compareAndSet(entries, Collections.unmodifiableList(newEntries))) {
                        scheduleCacheExpirationIfNeeded(ttl, loop);
                        return;
                    }
                } else if (compareAndSet(entries, Collections.singletonList(dnsRecord))) {
                    scheduleCacheExpirationIfNeeded(ttl, loop);
                    return;
                }
            }
        }

        private void scheduleCacheExpirationIfNeeded(int ttl, EventLoop loop) {
            while (true) {
                ScheduledFuture<?> oldFuture = FUTURE_UPDATER.get(this);
                if (null != oldFuture && oldFuture.getDelay(TimeUnit.SECONDS) <= ttl) {
                    break;
                }
                ScheduledFuture<?> newFuture = loop.schedule(this, ttl, TimeUnit.SECONDS);
                if (FUTURE_UPDATER.compareAndSet(this, oldFuture, newFuture)) {
                    if (null != oldFuture) {
                        oldFuture.cancel(true);
                    }
                    break;
                } else {
                    oldFuture.cancel(true);
                }
            }
        }

        boolean clearAndCancel() {
            final List<DnsRecord> entries = getAndSet(Collections.emptyList());
            if (entries.isEmpty()) {
                return false;
            }
            for (DnsRecord record : entries) {
                ReferenceCountUtil.release(record);
            }
            final ScheduledFuture expiration = FUTURE_UPDATER.getAndSet(this, null);
            if (null != expiration) {
                expiration.cancel(false);
            }
            return true;
        }

        @Override
        public void run() {
            resolveCache.remove(hostname, this);
            clearAndCancel();
        }
    }

}