package com.github.pangolin.routing.acceptor.mixin;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.acceptor.mixin.support.MixinAcceptorHandshakerFactory;
import com.github.pangolin.routing.acceptor.mixin.support.MixinServerHandshaker;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.support.handler.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.support.handler.server.Socks5ServerDatagramDemultiplexer;
import com.github.pangolin.routing.upstream.DynamicUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Maps;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 *
 */
@Slf4j
public class MixinAcceptor implements Acceptor {
    private static final Map<String, MixinAcceptorHandshakerFactory> HANDSHAKERS = initHandshakerFactories();

    private final List<MixinAcceptorHandshakerFactory> factories;

    private final int listenPort;
    private String upstream;

    public MixinAcceptor(final int listenPort, final String upstream, final String... factories) {
        this(listenPort, upstream, resolve(factories));
    }

    public MixinAcceptor(final int listenPort, final String upstream, final List<MixinAcceptorHandshakerFactory> factories) {
        this.listenPort = listenPort;
        this.upstream = upstream;
        this.factories = factories;
    }

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(final String upstream) {
        this.upstream = upstream;
    }


    @Override
    public ChannelFuture start(final RouteContext context) throws Exception {
        final SocketChannelFactory socketChannelFactory = getSocketChannelFactory(context);
        final DatagramChannelFactory datagramChannelFactory = getDatagramChannelFactory(context);

        final NettyServer server = new NettyServer(listenPort);
        return server.start(true, new Socks5ServerInitializer(datagramChannelFactory), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                final MixinServerHandshaker[] handshakers = factories
                        .stream()
                        .map(factory -> factory.createHandshaker(socketChannelFactory, datagramChannelFactory))
                        .toArray(MixinServerHandshaker[]::new);
                channel.pipeline().addLast(new MixinServerInitializer(handshakers));
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final Channel ch = future.channel();
                    final InetSocketAddress localAddress = (InetSocketAddress) ch.localAddress();
                    log.info("Mixed server started on port {}, bound to {} -> {}", localAddress.getPort(), localAddress, upstream);
                } else {
                    log.error("Mixed server started failed: {} for {}", future.cause().getMessage(), upstream, future.cause());
                }
            }
        });
    }

    private class Socks5ServerInitializer extends ChannelOutboundHandlerAdapter {
        private final DatagramChannelFactory datagramChannelFactory;

        private Socks5ServerInitializer(DatagramChannelFactory datagramChannelFactory) {
            this.datagramChannelFactory = datagramChannelFactory;
        }

        @Override
        public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
            promise.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        final Channel ch = future.channel();
                        ch.attr(Socks5ProxyServerHandler.UDP_CHANNEL_KEY).set(startSocks5UdpServer(ch, datagramChannelFactory));
                    }
                }
            });
            super.bind(ctx, localAddress, promise);
        }

    }

    private static ChannelFuture startSocks5UdpServer(final Channel tcpServerChannel, final DatagramChannelFactory datagramChannelFactory) {
        return new Bootstrap()
                .group(tcpServerChannel.eventLoop())
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        ch.pipeline().addLast(new Socks5ServerDatagramDemultiplexer(datagramChannelFactory));
                    }
                }).bind(0).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                            log.info("[SOCKS5] UDP started on port: {} ({}) -> TCP: {}", localAddress.getPort(), localAddress, tcpServerChannel.localAddress());
                        } else {
                            Throwable cause = future.cause();
                            log.info("[SOCKS5] UDP started failed: {} -> TCP: {}", cause.getMessage(), tcpServerChannel.localAddress(), cause);
                        }
                    }
                });
    }

    private SocketChannelFactory getSocketChannelFactory(final RouteContext context) {
        final Upstream upstreamToUse = new DynamicUpstream("mixin-socket-upstream") {

            @Override
            public boolean isAvailable() {
                return context.getUpstream(upstream).isAvailable();
            }

            @Override
            protected Upstream choose(final InetSocketAddress destination) {
                return context.getUpstream(upstream);
            }

        };
        return context.newSocketChannelFactory(upstreamToUse);
    }

    private DatagramChannelFactory getDatagramChannelFactory(final RouteContext context) {
        final Upstream upstreamToUse = new DynamicUpstream("mixin-datagram-upstream") {

            @Override
            public boolean isAvailable() {
                return context.getUpstream(upstream).isAvailable();
            }

            @Override
            protected Upstream choose(final InetSocketAddress destination) {
                return context.getUpstream(upstream);
            }

        };
        return context.newDatagramChannelFactory(upstreamToUse);
    }


    private static Map<String, MixinAcceptorHandshakerFactory> initHandshakerFactories() {
        final Iterable<MixinAcceptorHandshakerFactory> factories = ServiceLoader.load(MixinAcceptorHandshakerFactory.class);
        final Map<String, MixinAcceptorHandshakerFactory> handshakers = Maps.newLinkedHashMap();
        for (final MixinAcceptorHandshakerFactory factory : factories) {
            final String key = factory.name();
            if (handshakers.containsKey(key)) {
                log.warn("A MixinAcceptorHandshakerFactory named " + key
                        + " already exists, class: " + handshakers.get(key)
                        + ". It will be overwritten.");
            }
            handshakers.put(key, factory);
            log.info("Loaded MixinAcceptorHandshakerFactory [" + key + "]");
        }
        return handshakers;
    }

    private static List<MixinAcceptorHandshakerFactory> resolve(final String... factories) {
        return factories.length > 1 ? Arrays.stream(factories).map(name -> {
            final MixinAcceptorHandshakerFactory factory = HANDSHAKERS.get(name);
            if (null == factory) {
                throw new IllegalArgumentException("Unable to create MixinAcceptorHandshakerFactory with name " + name);
            }
            return factory;
        }).collect(Collectors.toList()) : Collections.emptyList();
    }
}
