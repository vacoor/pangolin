package com.github.pangolin.routing.server;

import com.github.pangolin.routing.acceptor.Forwarder;
import com.github.pangolin.routing.support.ProxySocketChannelFactory;
import com.github.pangolin.routing.support.handler.client.WebSocketProxyHandler;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import io.netty.channel.ChannelHandler;
import io.netty.channel.nio.NioEventLoopGroup;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.locks.LockSupport;

public class ForwarderTest {

    public static void main(String[] args) throws InterruptedException {
        ProxySocketChannelFactory factory = new ProxySocketChannelFactory(new AbstractUpstream("bridge") {
            @Override
            public SocketAddress address() {
                return null;
            }

            @Override
            public boolean isVirtual() {
                return false;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
//                return new WebSocketProxyHandler(URI.create("ws://127.0.0.1:2345/tunnel?agent=Local"), null);
                return new WebSocketProxyHandler(URI.create("ws://127.0.0.1:7777/ws/bridge/Local"), null, "c254dacd0cde3be75ac2988f691ec105");
            }

            @Override
            public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
                return null;
            }
        }, Collections.emptyList(), null);

        Forwarder forwarder = new Forwarder(factory, new NioEventLoopGroup(), new NioEventLoopGroup());
        forwarder.addForwarding(7777, InetSocketAddress.createUnresolved("192.168.1.4", 22));
        LockSupport.park();
    }
}