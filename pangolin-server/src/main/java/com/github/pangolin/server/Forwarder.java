package com.github.pangolin.server;

import com.github.pangolin.handler.SocketOverWebSocketDecodeHandler;
import com.github.pangolin.handler.SocketOverWebSocketEncodeHandler;
import com.github.pangolin.util.Channels;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import lombok.AllArgsConstructor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Forwarder {
    private final Discover discover;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConcurrentMap<SocketAddress, Forwarding> forwardingMap = new ConcurrentHashMap<>();

    public Forwarder(final Discover discover, final EventLoopGroup bossGroup, final EventLoopGroup workerGroup) {
        this.discover = discover;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
    }

    public Collection<Forwarding> getForwardings() {
        return forwardingMap.values();
    }

    public boolean removeForwarding(final int localPort) {
        return removeForwarding(new InetSocketAddress(localPort));
    }

    public boolean removeForwarding(final SocketAddress localAddr) {
        final Forwarding forwarding = forwardingMap.remove(localAddr);
        if (null != forwarding) {
            forwarding.boundChannel.close();
        }
        return null != forwarding;
    }

    public Forwarder addForwarding(final int localPort, final String agentKey, final InetSocketAddress remoteAddr) throws InterruptedException {
        return addForwarding(new InetSocketAddress(localPort), agentKey, remoteAddr);
    }

    public Forwarder addForwarding(final SocketAddress localAddr, final String agentKey, final InetSocketAddress remoteAddr) throws InterruptedException {
        final ChannelFuture boundChannelFuture = Channels.listen(localAddr, false, bossGroup, workerGroup, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext accessCtx) throws Exception {
                        final String id = "fw:" + accessCtx.channel().id().toString();
                        final URI target = URI.create("tcp://" + remoteAddr.getHostString() + ":" + remoteAddr.getPort());
                        final Promise<ChannelHandlerContext> promise = discover.tunnelRequested(id, agentKey, target, accessCtx);
                        accessCtx.channel().config().setAutoRead(false);
                        promise.addListener(new FutureListener<ChannelHandlerContext>() {
                            @Override
                            public void operationComplete(Future<ChannelHandlerContext> future) throws Exception {
                                if (future.isSuccess()) {
                                    final ChannelHandlerContext backhaulCtx = future.getNow();
                                    backhaulCtx.channel().config().setAutoRead(false);

                                    accessCtx.pipeline().replace(accessCtx.name(), null, new SocketOverWebSocketEncodeHandler(backhaulCtx));
                                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new SocketOverWebSocketDecodeHandler(accessCtx));

                                    accessCtx.channel().config().setAutoRead(true);
                                    backhaulCtx.channel().config().setAutoRead(true);
                                } else {
                                    accessCtx.fireExceptionCaught(future.cause());
                                }
                            }
                        });
                        super.channelActive(accessCtx);
                    }
                });
            }
        });

        forwardingMap.put(localAddr, new Forwarding(localAddr, agentKey, remoteAddr, boundChannelFuture.channel()));
        boundChannelFuture.channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                forwardingMap.remove(localAddr);
            }
        });
        boundChannelFuture.sync();

        return this;
    }

    @AllArgsConstructor
    private class Forwarding {
        private final SocketAddress localAddr;
        private final String agentKey;
        private final SocketAddress remoteAddr;
        private final Channel boundChannel;
    }
}
