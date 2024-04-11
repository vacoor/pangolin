package com.github.pangolin.routing.handler.internal.server.support;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.util.Channels;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.resolver.NoopAddressResolverGroup;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240411
 */
public class ProxyChannelFactory implements ChannelFactory {
  private final ProxyServer proxy;
  private final List<String> bypass;

  public ProxyChannelFactory(final ProxyServer proxy, final List<String> bypass) {
    this.proxy = proxy;
    this.bypass = null != bypass ? bypass : Collections.emptyList();
  }

  @Override
  public ChannelFuture open(final SocketAddress remoteAddress, final int connTimeoutMs, final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
    final ChannelHandler networkHandler = select(remoteAddress);
    return Channels.open(remoteAddress, NoopAddressResolverGroup.INSTANCE, connTimeoutMs, autoRead, group, new ChannelInitializer<SocketChannel>() {
      @Override
      protected void initChannel(final SocketChannel ch) throws Exception {
        if (null != networkHandler) {
          ch.pipeline().addFirst(networkHandler);
        }
        ch.pipeline().addLast(handler);
      }
    });
  }

  private ChannelHandler select(final SocketAddress destinationAddress) {
    if (null == proxy || !(destinationAddress instanceof InetSocketAddress)) {
      return null;
    }
    final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
    return !bypass.contains(sa.getHostString()) && !bypass.contains(sa.getHostName()) ? proxy.newProxyHandler(sa) : null;
  }
}
