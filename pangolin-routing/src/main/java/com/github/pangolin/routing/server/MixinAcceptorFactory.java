package com.github.pangolin.routing.server;

import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ServerDatagramDemultiplexer;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.MixinServerInitializer;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Maps;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

@Slf4j
public class MixinAcceptorFactory implements AcceptorFactory {
    private final Map<String, MixinAcceptorHandshakerFactory> handshakers = Maps.newLinkedHashMap();

    public MixinAcceptorFactory() {
        this(ServiceLoader.load(MixinAcceptorHandshakerFactory.class));
    }

    public MixinAcceptorFactory(final Iterable<MixinAcceptorHandshakerFactory> handshakers) {
        this.initHandshakerFactories(handshakers);
    }

    private void initHandshakerFactories(final Iterable<MixinAcceptorHandshakerFactory> factories) {
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
    }

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final String proxyName = args.length > 0 ? args[0] : null;
        final List<MixinAcceptorHandshakerFactory> factories = args.length > 1 ? Arrays.asList(args).subList(1, args.length).stream().map(name -> {
            final MixinAcceptorHandshakerFactory factory = handshakers.get(name);
            if (null == factory) {
                throw new IllegalArgumentException("Unable to create MixinAcceptorHandshakerFactory with name " + name);
            }
            return factory;
        }).collect(Collectors.toList()) : Collections.emptyList();

        final List<String> bypass = Arrays.asList("::1", "127.0.0.1", "localhost");
        return new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                final SocketChannelFactory socketChannelFactory = createSocketChannelFactory(context, proxyName);
                final DatagramChannelFactory datagramChannelFactory = createDatagramChannelFactory(context, proxyName);

                final NettyServer server = new NettyServer(listenPort);
                return server.start(true, new ChannelInitializer<SocketChannel>() {
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
                            log.info("Mixed upstream {} started on port {}, bound to {}", proxyName, localAddress.getPort(), localAddress);
                            ch.attr(Socks5ProxyServerHandler.UDP_CHANNEL_KEY).set(c(ch.eventLoop(), datagramChannelFactory));
                        } else {
                            future.cause().printStackTrace();
                        }
                    }
                });
            }
        };
    }

    private static ChannelFuture c(final EventLoopGroup group, final DatagramChannelFactory datagramChannelFactory) {
        return new Bootstrap()
//                .group(new NioEventLoopGroup())
//            .group(parent.eventLoop())
                .group(group)
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
                            log.info("[SOCKS5] UDP started on port: {} ({})", localAddress.getPort(), localAddress);
                        } else {
                            Throwable cause = future.cause();
                            log.info("[SOCKS5] UDP started failed: {}", cause.getMessage(), cause);
                        }
                    }
                });
    }


    protected SocketChannelFactory createSocketChannelFactory(final RouteContext context, final String upstream) {
        return "DEFAULT".equals(upstream) ? context.newSocketChannelFactory() : context.newSocketChannelFactory(upstream);
    }

    protected DatagramChannelFactory createDatagramChannelFactory(final RouteContext context, final String upstream) {
        return "DEFAULT".equals(upstream) ? context.newDatagramChannelFactory() : context.newDatagramChannelFactory(upstream);
    }

    /*
    public static void main(String[] args) throws Exception {
        final Acceptor acceptor = new MixinAcceptorFactory().apply(1089);
        acceptor.start(new InMemoryRouteContext(null)).sync().channel().closeFuture().sync();
    }
    */

}
