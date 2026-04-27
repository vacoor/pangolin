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
public class WebSocketBridgeServerForwarder {
    private final WebSocketBridgeServerEngine engine;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConcurrentMap<SocketAddress, Forwarding> registeredForwardingMap = new ConcurrentHashMap<>();

    /**
     * Create a forwarder.
     *
     * @param engine      the server engine
     * @param bossGroup   the boss event loop group
     * @param workerGroup the worker event loop group
     */
    public WebSocketBridgeServerForwarder(final WebSocketBridgeServerEngine engine,
                                          final EventLoopGroup bossGroup, final EventLoopGroup workerGroup) {
        this.engine = engine;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
    }

    /**
     * Add a forwarding rule by local port.
     *
     * @param localPort  the local port
     * @param tunnelKey  the tunnel key
     * @param remoteAddr the remote address
     * @return this forwarder
     * @throws InterruptedException if the current thread is interrupted
     */
    public WebSocketBridgeServerForwarder addForwarding(final int localPort,
                                                        final String tunnelKey,
                                                        final InetSocketAddress remoteAddr) throws InterruptedException {
        return addForwarding(new InetSocketAddress(localPort), tunnelKey, remoteAddr);
    }

    /**
     * Add a forwarding rule.
     *
     * @param localAddr the local address
     * @param tunnelKey the tunnel key
     * @param target    the target address
     * @return this forwarder
     * @throws InterruptedException if the current thread is interrupted
     */
    public WebSocketBridgeServerForwarder addForwarding(final SocketAddress localAddr,
                                                        final String tunnelKey,
                                                        final InetSocketAddress target) throws InterruptedException {
        Channels.listen(localAddr, false, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext accessCtx) throws Exception {
                        final String id = accessCtx.channel().id().toString();
                        final SocketAddress source = accessCtx.channel().remoteAddress();
                        log.info("[{}] Establishing connection from {} to {} via {}", id, source, target, tunnelKey);

                        engine.handshake(accessCtx, tunnelKey, target).addListener(new FutureListener<ChannelHandlerContext>() {
                            @Override
                            public void operationComplete(Future<ChannelHandlerContext> future) throws Exception {
                                if (future.isSuccess()) {
                                    final ChannelHandlerContext backhaulCtx = future.getNow();
                                    backhaulCtx.channel().config().setAutoRead(false);
                                    backhaulCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                                        @Override
                                        public void operationComplete(final ChannelFuture future) throws Exception {
                                            if (accessCtx.channel().isActive()) {
                                                log.info("[{}] Connection closed by agent: from {} to {} via {}", id, source, target, tunnelKey);
                                            } else {
                                                log.info("[{}] Connection closed by client: from {} to {} via {}", id, source, target, tunnelKey);
                                            }
                                        }
                                    });

                                    log.info("[{}] Connection established from {} to {} via {}", id, source, target, tunnelKey);

                                    /*-
                                     * client <--socket--> server <--ws--> agent
                                     */
                                    accessCtx.pipeline().replace(accessCtx.name(), null, new TcpOverWebSocketEncodeHandler(backhaulCtx));

                                    backhaulCtx.pipeline().addBefore(backhaulCtx.name(), "backhaul-keepalive", new WebSocketKeepaliveHandler(60, 60, 60));
                                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpOverWebSocketDecodeHandler(accessCtx));

                                    accessCtx.channel().config().setAutoRead(true);
                                    backhaulCtx.channel().config().setAutoRead(true);
                                } else {
                                    final Throwable cause = future.cause();
                                    log.warn("[{}] Connection from {} to {} via {} failed: {}", id, source, target, tunnelKey, cause.getMessage());

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
                    log.info("Forwarding local connections from {} to remote address {} via {}", localAddr, target, tunnelKey);

                    final Forwarding forwarding = new Forwarding(localAddr, tunnelKey, target, future.channel());
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

    /**
     * Remove a forwarding rule by local port.
     *
     * @param localPort the local port
     * @return true if the forwarding rule is removed; otherwise false
     */
    public boolean removeForwarding(final int localPort) {
        return removeForwarding(new InetSocketAddress(localPort));
    }

    /**
     * Remove a forwarding rule.
     *
     * @param localAddr the local address
     * @return true if the forwarding rule is removed; otherwise false
     */
    public boolean removeForwarding(final SocketAddress localAddr) {
        final Forwarding forwarding = registeredForwardingMap.remove(localAddr);
        if (null != forwarding) {
            log.info("Closed local forwarding: {} to {} via {}", localAddr, forwarding.remoteAddr, forwarding.tunnelKey);

            forwarding.boundChannel.close();
        }
        return null != forwarding;
    }

    /**
     * Get all registered forwarding rules.
     *
     * @return the registered forwarding rules
     */
    public Collection<Forwarding> getForwardings() {
        return registeredForwardingMap.values();
    }

    /**
     * A forwarding rule.
     */
    @Getter
    @AllArgsConstructor
    public class Forwarding {
        /**
         * The local address.
         */
        private final SocketAddress localAddr;

        /**
         * The tunnel key.
         */
        private final String tunnelKey;

        /**
         * The remote address.
         */
        private final SocketAddress remoteAddr;

        /**
         * The bound channel.
         */
        private final Channel boundChannel;
    }
}
