package com.github.pangolin.routing.handler.internal.server.support;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.SocketAddress;

public class StandardChannelFactory implements ChannelFactory {

  @Override
  public ChannelFuture open(final SocketAddress remoteAddress,
                            final int connTimeoutMs, final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
    final Bootstrap b = new Bootstrap();
    b.option(ChannelOption.AUTO_READ, autoRead);
    b.option(ChannelOption.TCP_NODELAY, true);
    b.option(ChannelOption.SO_KEEPALIVE, true);
    b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connTimeoutMs);
    b.option(ChannelOption.SO_RCVBUF, 32 * 1024);// 读缓冲区为32k
    b.group(group).channel(NioSocketChannel.class).handler(handler);
    return b.connect(remoteAddress);
  }

}