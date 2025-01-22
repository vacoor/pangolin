package com.github.pangolin.routing.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.util.Channels;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class Forwarder {
  private final SocketChannelFactory factory;
  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;
  private final ConcurrentMap<SocketAddress, Forwarding> registeredForwardingMap = new ConcurrentHashMap<>();

  public Forwarder(final SocketChannelFactory factory, final EventLoopGroup bossGroup, final EventLoopGroup workerGroup) {
    this.factory = factory;
    this.bossGroup = bossGroup;
    this.workerGroup = workerGroup;
  }

  public Collection<Forwarding> getForwardings() {
    return registeredForwardingMap.values();
  }

  public boolean removeForwarding(final int localPort) {
    return removeForwarding(new InetSocketAddress(localPort));
  }

  public boolean removeForwarding(final SocketAddress localAddr) {
    final Forwarding forwarding = registeredForwardingMap.remove(localAddr);
    if (null != forwarding) {
      log.info("Local forwarding '{}' removed", localAddr);
      forwarding.boundChannel.close();
    }
    return null != forwarding;
  }

  public Forwarder addForwarding(final int localPort, final InetSocketAddress remoteAddr) throws InterruptedException {
    return addForwarding(new InetSocketAddress(localPort), remoteAddr);
  }

  public Forwarder addForwarding(final SocketAddress localAddr, final InetSocketAddress destination) throws InterruptedException {
    final ChannelFuture boundChannelFuture = Channels.listen(localAddr, false, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
      @Override
      protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(new LoggingHandler());
        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
          @Override
          public void channelActive(final ChannelHandlerContext accessCtx) throws Exception {
            accessCtx.fireChannelActive();
            accessCtx.channel().config().setAutoRead(false);

            factory.open(destination, accessCtx.channel().config().getConnectTimeoutMillis(), false, accessCtx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addFirst(new LoggingHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                  @Override
                  public void channelActive(final ChannelHandlerContext backhaulCtx) throws Exception {
                    // tcp over websocket.
                    accessCtx.pipeline().replace(accessCtx.name(), null, new TcpInboundRedirectHandler(backhaulCtx));
                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpInboundRedirectHandler(accessCtx));

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);
                  }
                });
              }
            });
          }
        });
      }
    });

    log.info("Local forwarding {} = {} added", localAddr, destination);

    registeredForwardingMap.put(localAddr, new Forwarding(localAddr, destination, boundChannelFuture.channel()));
    boundChannelFuture.channel().closeFuture().addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        registeredForwardingMap.remove(localAddr);
      }
    });
    boundChannelFuture.sync();

    return this;
  }

  @Getter
  @AllArgsConstructor
  public class Forwarding {
    private final SocketAddress localAddr;
    private final SocketAddress remoteAddr;
    private final Channel boundChannel;
  }
}
