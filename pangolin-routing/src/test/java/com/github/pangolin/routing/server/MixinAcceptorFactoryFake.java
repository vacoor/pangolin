package com.github.pangolin.routing.server;

import com.github.pangolin.routing.beta.fakedns.*;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.MixinServerInitializer;
import com.github.pangolin.routing.support.ProxyDatagramChannelFactory;
import com.github.pangolin.routing.support.ProxySocketChannelFactory;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Maps;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

@Slf4j
public class MixinAcceptorFactoryFake implements AcceptorFactory {
    private final Map<String, MixinAcceptorHandshakerFactory> handshakers = Maps.newLinkedHashMap();

    public MixinAcceptorFactoryFake() {
        this(ServiceLoader.load(MixinAcceptorHandshakerFactory.class));
    }

    public MixinAcceptorFactoryFake(final Iterable<MixinAcceptorHandshakerFactory> handshakers) {
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

                final Upstream upstream = "DEFAULT".equals(proxyName) ? new AbstractUpstream("DEFAULT") {
                    @Override
                    public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
                        final Upstream upstream = context.choose(destination);
                        return null != upstream ? upstream.newSocketProxyHandler(destination) : null;
                    }

                    @Override
                    public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
                        final Upstream upstream = context.choose(destination);
                        return null != upstream ? upstream.newDatagramProxyHandler(destination) : null;
                    }
                } : context.getUpstream(proxyName);

                final DnsEngine fakeDns = context.attr(DnsEngine.class.getName());
                // FIXME
                final SocketChannelFactory routeSocketFactory = null != upstream
                        ? new FakeDnsSocketChannelFactory(fakeDns, new ProxySocketChannelFactory(upstream, bypass))
                        : new StandardSocketChannelFactory();
                final DatagramChannelFactory routeDatagramFactory = null != upstream
                        ? new FakeDnsDatagramChannelFactory(fakeDns, new ProxyDatagramChannelFactory(upstream, bypass))
                        : new StandardDatagramChannelFactory();

                final NettyServer server = new NettyServer(listenPort);
                return server.start(true, new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel channel) throws Exception {
                        final MixinServerHandshaker[] handshakers = factories
                                .stream()
                                .map(factory -> factory.createHandshaker(routeSocketFactory, routeDatagramFactory))
                                .toArray(MixinServerHandshaker[]::new);
                        channel.pipeline().addLast(new MixinServerInitializer(handshakers));
                    }
                }).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                            log.info("Mixed upstream {} started on port {}, bound to {}", proxyName, localAddress.getPort(), localAddress);
                        } else {
                            future.cause().printStackTrace();
                        }
                    }
                });
            }
        };
    }

}
