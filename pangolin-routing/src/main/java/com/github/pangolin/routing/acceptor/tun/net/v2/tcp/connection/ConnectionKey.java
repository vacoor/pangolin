package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection;

/**
 * Type-safe per-connection attribute key, analogous to Netty's {@code AttributeKey<T>}.
 * RFC extensions store their per-connection state in {@link TcpConnection}'s attribute map
 * using a unique key instance.
 *
 * <p>Naming convention: {@code "{rfc-id}.{impl-name}"}, e.g. {@code "rfc5681.newreno"}.
 * Key identity is based on object identity (not name), so callers must hold a static reference.
 */
public final class ConnectionKey<T> {

    private final String name;

    private ConnectionKey(String name) {
        this.name = name;
    }

    /**
     * Create a new key with the given name.
     * The returned instance is the canonical key — store it as a {@code static final} field.
     */
    public static <T> ConnectionKey<T> of(String name) {
        return new ConnectionKey<>(name);
    }

    @Override
    public String toString() { return name; }
}
