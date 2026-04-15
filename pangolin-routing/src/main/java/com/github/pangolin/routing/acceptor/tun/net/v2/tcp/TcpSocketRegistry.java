package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.sock.V2TcpSock;
import io.netty.buffer.ByteBufAllocator;

import java.util.HashMap;

/**
 * v2 registry with v1-style socket lifecycle:
 * LISTEN sock + SYN(req) table + ESTABLISHED table.
 *
 * <p>Single-writer contract: all access should happen on TUN EventLoop.
 */
public final class TcpSocketRegistry {

    private final V2TcpSock listenSock;
    private final HashMap<FourTuple, tcp_request_sock> synRegistry = new HashMap<>();
    private final HashMap<FourTuple, V2TcpSock> establishedRegistry = new HashMap<>();

    public TcpSocketRegistry(ByteBufAllocator allocator) {
        this.listenSock = new V2TcpSock(allocator);
        this.listenSock.state(TcpState.TCP_LISTEN);
    }

    public V2TcpSock listenSock() {
        return listenSock;
    }

    public tcp_request_sock syn(FourTuple key) {
        return synRegistry.get(key);
    }

    public void putSyn(FourTuple key, tcp_request_sock req) {
        synRegistry.put(key, req);
    }

    public void removeSyn(FourTuple key) {
        synRegistry.remove(key);
    }

    public V2TcpSock established(FourTuple key) {
        return establishedRegistry.get(key);
    }

    public void putEstablished(FourTuple key, V2TcpSock sock) {
        establishedRegistry.put(key, sock);
    }

    public void removeEstablished(FourTuple key) {
        establishedRegistry.remove(key);
    }

    public int synSize() {
        return synRegistry.size();
    }

    public int establishedSize() {
        return establishedRegistry.size();
    }
}
