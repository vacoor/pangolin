package com.github.pangolin.server;

import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
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
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class WebSocketBackhaulTunnelServerForwarder {
    private final WebSocketBackhaulTunnelServerEngine webSocketBackhaulTunnelServerEngine;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConcurrentMap<SocketAddress, Forwarding> registeredForwardingMap = new ConcurrentHashMap<>();

    public WebSocketBackhaulTunnelServerForwarder(final WebSocketBackhaulTunnelServerEngine webSocketBackhaulTunnelServerEngine, final EventLoopGroup bossGroup, final EventLoopGroup workerGroup) {
        this.webSocketBackhaulTunnelServerEngine = webSocketBackhaulTunnelServerEngine;
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

    public WebSocketBackhaulTunnelServerForwarder addForwarding(final int localPort, final String agent, final InetSocketAddress remoteAddr) throws InterruptedException {
        return addForwarding(new InetSocketAddress(localPort), agent, remoteAddr);
    }

    public WebSocketBackhaulTunnelServerForwarder addForwarding(final SocketAddress localAddr, final String agent, final InetSocketAddress remoteAddr) throws InterruptedException {
        final ChannelFuture boundChannelFuture = Channels.listen(localAddr, false, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext accessCtx) throws Exception {
                        final String id = "F:" + accessCtx.channel().id().toString();
                        final URI target = URI.create("tcp://" + remoteAddr.getHostString() + ":" + remoteAddr.getPort());
                        webSocketBackhaulTunnelServerEngine.tunnelRequested(id, agent, target, accessCtx, accessCtx.executor().newPromise()).addListener(new FutureListener<ChannelHandlerContext>() {
                            @Override
                            public void operationComplete(Future<ChannelHandlerContext> future) throws Exception {
                                if (future.isSuccess()) {
                                    final ChannelHandlerContext backhaulCtx = future.getNow();
                                    backhaulCtx.channel().config().setAutoRead(false);

                                    // tcp over websocket.
                                    accessCtx.pipeline().replace(accessCtx.name(), null, new TcpOverWebSocketEncodeHandler(backhaulCtx));
                                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpOverWebSocketDecodeHandler(accessCtx));

                                    accessCtx.channel().config().setAutoRead(true);
                                    backhaulCtx.channel().config().setAutoRead(true);
                                } else {
                                    accessCtx.fireExceptionCaught(future.cause());
                                }
                            }
                        });
                        accessCtx.fireChannelActive();
                    }
                });
            }
        });

        log.info("Local forwarding {} = {} => {} added", localAddr, agent, remoteAddr);

        registeredForwardingMap.put(localAddr, new Forwarding(localAddr, agent, remoteAddr, boundChannelFuture.channel()));
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
        private final String agentKey;
        private final SocketAddress remoteAddr;
        private final Channel boundChannel;
    }
}
