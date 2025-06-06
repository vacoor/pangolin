package com.github.pangolin.server;

import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.util.Channels;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class WebSocketBackhaulTunnelServerForwarder {
    private final WebSocketBackhaulTunnelServerEngine engine;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConcurrentMap<SocketAddress, Forwarding> registeredForwardingMap = new ConcurrentHashMap<>();

    public WebSocketBackhaulTunnelServerForwarder(final WebSocketBackhaulTunnelServerEngine engine, final EventLoopGroup bossGroup, final EventLoopGroup workerGroup) {
        this.engine = engine;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
    }

    public WebSocketBackhaulTunnelServerForwarder addForwarding(final int localPort,
                                                                final String agentKey,
                                                                final InetSocketAddress remoteAddr) throws InterruptedException {
        return addForwarding(new InetSocketAddress(localPort), agentKey, remoteAddr);
    }

    public WebSocketBackhaulTunnelServerForwarder addForwarding(final SocketAddress localAddr,
                                                                final String agentKey,
                                                                final InetSocketAddress target) throws InterruptedException {
        Channels.listen(localAddr, false, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext accessCtx) throws Exception {
                        log.info("[{}] Establishing Connection to {} via {}", accessCtx.channel().id(), target, agentKey);

                        engine.handshake(
                                accessCtx, agentKey, target, accessCtx.executor().newPromise()
                        ).addListener(new FutureListener<ChannelHandlerContext>() {
                            @Override
                            public void operationComplete(Future<ChannelHandlerContext> future) throws Exception {
                                if (future.isSuccess()) {
                                    final ChannelHandlerContext backhaulCtx = future.getNow();
                                    backhaulCtx.channel().config().setAutoRead(false);

                                    log.info("[{}] Connection established to {} via {}", accessCtx.channel().id(), target, agentKey);

                                    /*-
                                     * client <--socket--> server <--ws--> agent
                                     */
                                    accessCtx.pipeline().replace(accessCtx.name(), null, new TcpOverWebSocketEncodeHandler(backhaulCtx));
                                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpOverWebSocketDecodeHandler(accessCtx));

                                    accessCtx.channel().config().setAutoRead(true);
                                    backhaulCtx.channel().config().setAutoRead(true);
                                } else {
                                    final Throwable cause = future.cause();
                                    log.warn("[{}] Connection to {} via {} failed: {}", accessCtx.channel().id(), target, agentKey, cause.getMessage());

                                    accessCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                                }
                            }
                        });
                        accessCtx.fireChannelActive();
                    }
                });
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    log.info("Local connections to {} forwarded to remote address {} via {}", localAddr, target, agentKey);
                    final Forwarding forwarding = new Forwarding(localAddr, agentKey, target, future.channel());
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

    public Collection<Forwarding> getForwardings() {
        return registeredForwardingMap.values();
    }

    @Getter
    @AllArgsConstructor
    public class Forwarding {
        private final SocketAddress localAddr;
        private final String agentKey;
        private final SocketAddress remoteAddr;
        private final Channel boundChannel;
    }
}
