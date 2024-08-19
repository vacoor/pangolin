package com.github.pangolin.routing.beta.tun.fakedns;

import java.util.concurrent.atomic.AtomicInteger;

public class LeaseAllocator4 extends LeaseAllocator<LeaseAllocator4.Lease> {

    @Override
    protected boolean check(final Lease item) {
        return System.currentTimeMillis() - item.timestamp >= ttl;
    }

    protected class Lease {
        public final int value;
        public final long timestamp;

        protected Lease(int value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final int min;
    private final int max;
    private final AtomicInteger generator;
    private final long ttl;

    public LeaseAllocator4(final int min, final int max, final long ttl) {
        this.min = min;
        this.max = max;
        generator = new AtomicInteger(min);
        this.ttl = ttl;
    }

    public Lease acquire() {
        Lease lease = poll(2 * ttl);
        if (null == lease) {
            lease = acquire0();
        }
        if (null == lease) {
            lease = poll(ttl);
        }

        if (null == lease) {
            throw new IllegalStateException("No more");
        }
        lease = new Lease(lease.value);
        offer(lease);
        return lease;
    }

    private Lease acquire0() {
        int value;
        do {
            value = generator.get();
            if (value > max) {
                return null;
            }
        } while(!generator.compareAndSet(value, value + 1));
        return new Lease(value);
    }
}
