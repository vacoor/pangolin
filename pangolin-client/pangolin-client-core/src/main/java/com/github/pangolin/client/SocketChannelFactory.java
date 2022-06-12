package com.github.pangolin.client;

import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.SocketChannel;

public interface SocketChannelFactory {

    SocketChannel newSocketChannel(final String host, final int port,
                                   final int connectTimeoutMs, final ChannelHandler handler) throws InterruptedException;

}