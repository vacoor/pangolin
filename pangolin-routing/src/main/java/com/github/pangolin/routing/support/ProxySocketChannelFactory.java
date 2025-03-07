package com.github.pangolin.routing.support;

import com.github.pangolin.routing.upstream.Upstream;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ProxySocketChannelFactory implements SocketChannelFactory {
    private final Upstream upstream;
    private final List<String> bypass;
    private final SocketAddress localAddress;
    private final boolean mp = false;
    private final SocketChannelFactory factory = new StandardSocketChannelFactory(null);

    public ProxySocketChannelFactory(final Upstream upstream,
                                     final List<String> bypass,
                                     final SocketAddress localAddress) {
        this.upstream = ObjectUtil.checkNotNull(upstream, "upstream");
        this.bypass = null != bypass ? bypass : Collections.emptyList();
        this.localAddress = localAddress;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChannelFuture open(final SocketAddress destination, final int connTimeoutMs,
                              final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
        if (mp && !isByPass(destination)) {
            ChannelFuture select = select((InetSocketAddress) destination, connTimeoutMs, autoRead, group, handler);
            if (null != select) {
                return select;
            }
        }

        final ChannelHandler transport = !isByPass(destination) ? upstream.newSocketProxyHandler((InetSocketAddress) destination) : null;
        final NoopAddressResolverGroup resolverGroup = null != transport ? NoopAddressResolverGroup.INSTANCE : null;

        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, autoRead);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connTimeoutMs);
//        b.option(ChannelOption.SO_RCVBUF, 32 * 1024);// 读缓冲区为32k
        b.resolver(resolverGroup)
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        if (null != transport) {
                            ch.pipeline().addLast(transport);
                        }
                        ch.pipeline().addLast(handler);
                    }
                });
        return b.connect(destination, localAddress);
    }


    private ChannelFuture select(final InetSocketAddress addr, final int connTimeoutMs,
                                        final boolean autoRead, final EventLoopGroup group, final ChannelHandler handler) {
        final Promise<ChannelFuture> promise = GlobalEventExecutor.INSTANCE.newPromise();
        final ChannelHandler[] handlers = upstream.newSocketProxyHandlers(addr);
        if (handlers.length < 1) {
            return null;
        }
        int i = 0;
        for (ChannelHandler transport : handlers) {
            if (i > 5) {
                break;
            }

            ChannelFuture cf = factory.open(addr, connTimeoutMs, autoRead, group, new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(final Channel ch) {
                    if (null != transport) {
                        ch.pipeline().addLast(transport);
                    }
//                    ch.pipeline().addLast(handler);
                }
            }).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (promise.isSuccess() || !future.isSuccess() || !promise.trySuccess(future)) {
                        future.channel().close();
                    } else {
                        future.channel().pipeline().addLast(handler);
                    }
                }
            });
            promise.addListener(new GenericFutureListener<Future<ChannelFuture>>() {
                @Override
                public void operationComplete(final Future<ChannelFuture> future) throws Exception {
                    if (future.isSuccess() && !future.get().equals(cf)) {
                        cf.channel().close();
                    }
                }
            });
            i++;
        }

        try {
            return promise.await().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }



    private boolean isByPass(final SocketAddress destination) {
        if (destination instanceof InetSocketAddress) {
            final InetSocketAddress address = (InetSocketAddress) destination;
            final String hostname = address.isUnresolved() ? address.getHostString() : address.getHostName();
            if (!bypass.contains(hostname)) {
                return false;
            }
            log.info("[ROUTING] {}:{} will bypass the upstream", hostname, address.getPort());
        } else {
            log.debug("[ROUTING] UNSUPPORTED_ADDRESS {} will bypass the upstream", destination);
        }
        return true;
    }

}
