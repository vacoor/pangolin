package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpSockChannel;

import java.util.HashMap;

/**
 * Registry of active TCP connections, keyed by {@link FourTuple}.
 *
 * <p><b>Single-writer invariant</b>: all reads and writes must occur on the TUN EventLoop.
 * A plain {@link HashMap} is used (no {@code ConcurrentHashMap}) because the single-writer
 * constraint is enforced by caller discipline (enforced via {@code assert} in debug builds).
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Register on first SYN (TUN EventLoop, {@code TcpMultiplexHandler.channelRead})</li>
 *   <li>Remove via {@code deregisterCallback} posted from {@code TcpSockChannel.doClose()}</li>
 * </ul>
 */
public final class TcpConnectionRegistry {

    private final HashMap<FourTuple, TcpSockChannel> map = new HashMap<>();

    /** @return the channel for this 4-tuple, or {@code null} if not found. */
    public TcpSockChannel get(FourTuple key) {
        return map.get(key);
    }

    /** Register a new connection. Must be called on the TUN EventLoop. */
    public void put(FourTuple key, TcpSockChannel channel) {
        map.put(key, channel);
    }

    /** Remove a connection. Must be called on the TUN EventLoop. */
    public void remove(FourTuple key) {
        map.remove(key);
    }

    public int size() { return map.size(); }
}
