package com.github.pangolin.routing.beta.tun.fakedns.beta;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InetAddressPool<T extends InetAddress> {
    private final InetAddressFactory<T> factory;
    private final ConcurrentLinkedQueue<T> expired = new ConcurrentLinkedQueue<>();

    public InetAddressPool(final InetAddressFactory<T> factory) {
        this.factory = factory;
    }

    public T acquire() {
        final T lease = expired.poll();
        if (null != lease) {
            return lease;
        }
        return factory.create();
    }

    void release(T address) {
        expired.offer(address);
    }
}