package com.github.pangolin.routing.acceptor.tun.fakedns;

public interface FakeDns {

    boolean isFake(final byte[] addr);

    String lookup(final byte[] addr);

    String tryReference(final byte[] addr);

    boolean tryRelease(final byte[] addr, final String hostname);

    byte[] tryAcquire(final String hostname, final long ttl);

}
