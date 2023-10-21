package com.github.pangolin.routing.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.util.Channels;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.NoopAddressResolverGroup;
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
    private final ProxyServerProvider proxyServerProvider;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConcurrentMap<SocketAddress, Forwarding> registeredForwardingMap = new ConcurrentHashMap<>();

    public Forwarder(final ProxyServerProvider proxyServerProvider, final EventLoopGroup bossGroup, final EventLoopGroup workerGroup) {
        this.proxyServerProvider = proxyServerProvider;
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

    public Forwarder addForwarding(final int localPort, final String agent, final InetSocketAddress remoteAddr) throws InterruptedException {
        return addForwarding(new InetSocketAddress(localPort), agent, remoteAddr);
    }

    public Forwarder addForwarding(final SocketAddress localAddr, final String agent, final InetSocketAddress destination) throws InterruptedException {
        final ChannelFuture boundChannelFuture = Channels.listen(localAddr, false, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext accessCtx) throws Exception {
                        accessCtx.fireChannelActive();
                        ProxyServer instance = proxyServerProvider.getInstance(agent);
                        if (null == instance) {
                            throw new IllegalStateException("instance loosed: " + agent);
                        }

                        accessCtx.channel().config().setAutoRead(false);

                        final ChannelHandler networkHandler = instance.newProxyHandler(destination);
                        Channels.open(destination, NoopAddressResolverGroup.INSTANCE, false, accessCtx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(final SocketChannel ch) throws Exception {
                                if (null != networkHandler) {
                                    ch.pipeline().addFirst(networkHandler);
                                }
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

        log.info("Local forwarding {} = {} => {} added", localAddr, agent, destination);

        registeredForwardingMap.put(localAddr, new Forwarding(localAddr, agent, destination, boundChannelFuture.channel()));
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
