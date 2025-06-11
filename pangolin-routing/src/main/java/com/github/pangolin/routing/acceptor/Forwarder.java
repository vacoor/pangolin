package com.github.pangolin.routing.acceptor;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.util.Channels;
import io.netty.channel.*;
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

    public Forwarder addForwarding(final int localPort, final InetSocketAddress remoteAddr) throws InterruptedException {
        return addForwarding(new InetSocketAddress(localPort), remoteAddr);
    }

    public Forwarder addForwarding(final SocketAddress localAddr, final InetSocketAddress target) throws InterruptedException {
        Channels.listen(localAddr, false, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext downstreamCtx) throws Exception {
                        final String id = downstreamCtx.channel().id().toString();
                        final SocketAddress source = downstreamCtx.channel().remoteAddress();
                        log.info("[{}] Establishing Connection from {} to {}", id, source, target);

                        factory.open(target, downstreamCtx.channel().config().getConnectTimeoutMillis(), false, downstreamCtx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(final SocketChannel ch) throws Exception {
                                ch.pipeline().addFirst(new LoggingHandler());
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelActive(final ChannelHandlerContext upstreamCtx) throws Exception {
                                        downstreamCtx.pipeline().replace(downstreamCtx.name(), null, new TcpInboundRedirectHandler(upstreamCtx));
                                        upstreamCtx.pipeline().replace(upstreamCtx.name(), null, new TcpInboundRedirectHandler(downstreamCtx));

                                        downstreamCtx.channel().config().setAutoRead(true);
                                        upstreamCtx.channel().config().setAutoRead(true);
                                    }
                                });
                            }
                        }).addListener(new ChannelFutureListener() {

                            @Override
                            public void operationComplete(final ChannelFuture future) throws Exception {
                                if (future.isSuccess()) {
                                    log.info("[{}] Connection established from {} to {}", id, source, target);
                                } else {
                                    log.warn("[{}] Connection from {} to {} failed: {}", id, source, target, future.cause().getMessage());
                                }
                            }

                        });
                    }
                });
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    log.info("Local connections to {} forwarded to remote address {}", localAddr, target);

                    final Forwarding forwarding = new Forwarding(localAddr, target, future.channel());
                    registeredForwardingMap.put(localAddr, forwarding);
                }
            }
        }).sync().channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                registeredForwardingMap.remove(localAddr);
            }
        });

        return this;
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
            log.info("Closed local forwarding: {} to {}", localAddr, forwarding.remoteAddr);
            forwarding.boundChannel.close();
        }
        return null != forwarding;
    }

    @Getter
    @AllArgsConstructor
    public class Forwarding {
        private final SocketAddress localAddr;
        private final SocketAddress remoteAddr;
        private final Channel boundChannel;
    }
}
