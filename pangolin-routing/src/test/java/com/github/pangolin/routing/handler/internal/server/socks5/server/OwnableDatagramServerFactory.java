package com.github.pangolin.routing.handler.internal.server.socks5.server;

import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 *
 */
public interface OwnableDatagramServerFactory {

    interface OwnableDatagramServer {

        InetAddress owner();

        InetSocketAddress localAddress();

        Promise<Void> shutdownGracefully();

    }

    OwnableDatagramServer bind(final int port, final InetAddress owner);

    OwnableDatagramServer bind(final String host, final int port, final InetAddress owner);

}
