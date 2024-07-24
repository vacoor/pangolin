package com.github.pangolin.routing.v2.support;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.Ini;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.MixinServerInitializer;
import com.github.pangolin.routing.v2.context.RouteContext;
import com.github.pangolin.routing.v2.context.DefaultRouteContext;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.server.AcceptorFactory;
import com.github.pangolin.routing.v2.server.MixinAcceptorFactory;
import com.github.pangolin.routing.v2.server.MixinAcceptorHandshakerFactory;
import com.github.pangolin.routing.v2.server.Acceptor;
import com.github.pangolin.routing.v2.upstream.AbstractUpstream;
import com.github.pangolin.routing.v2.upstream.Upstream;
import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamFactory;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

@Slf4j
public class DefaultServerReader extends ReaderSupport {
    private final AcceptorFactory acceptorFactory = new MixinAcceptorFactory();

    public DefaultServerReader(final LoadBalancerStats stats,
                               final Iterable<UpstreamFactory> factories,
                               final Iterable<UpstreamCombiner> combiners,
                               final Iterable<RoutePredicateFactory<InetSocketAddress, String>> predicates) {
        super(stats, factories, combiners, predicates);
        this.initMixinServerHandshakerFactories();
    }

    public RouteContext load(final URL url, final RouteContext parent) throws Exception {
        final Ini ini = new Ini();
        ini.load(url.openStream());

        final Ini.Section external = ini.getSection("External");
        RouteContext parentToUse = parent;
        if (null != external) {
            for (String urlToUse : external.values()) {
                parentToUse = load(urlToUse, parentToUse);
            }
        }

        final DefaultRouteContext registry = new DefaultRouteContext(parentToUse);
        final Ini.Section proxy = ini.getSection("Proxy");
        if (null != proxy) {
            proxy.forEach((k, v) -> registry.addUpstream(k, apply(k, v)));
        }

        final Ini.Section proxyGroups = ini.getSection("Proxy Group");
        if (null != proxyGroups) {
            for (final Map.Entry<String, String> entry : proxyGroups.entrySet()) {
                final String name = entry.getKey();
                final String value = entry.getValue();
                final String[] segments = value.split("\\s*,\\s*");
                final String type = segments[0];
                final List<String> proxies = Arrays.asList(Arrays.copyOfRange(segments, 1, segments.length));

                registry.addUpstream(name, apply(name, type, proxies, registry));
            }
        }

        Ini.Section rule = ini.getSection("Rule");
        if (null != rule) {
            rule.keySet().stream().map(route -> apply(route, url)).forEach(registry::addRoute);
        }


        // TODO
        Ini.Section listen = ini.getSection("Listen");
        if (null == listen) {
            listen = ini.addSection("Listen");
            listen.put("1080", "DEFAULT, SOCKS5, SOCKS4, HTTP");
        }

        for (final Map.Entry<String, String> entry : listen.entrySet()) {
            final String port = entry.getKey();
            final String definition = entry.getValue();
            final int listenPort = Integer.parseInt(port);
            final String[] segments = definition.split("\\s*,\\s*");
            if (segments.length < 2) {
                throw new IllegalArgumentException("Unable to create Acceptor with definition " + definition);
            }

            final Acceptor acceptor = acceptorFactory.apply(listenPort, segments);
            acceptor.start(registry);
        };

        return registry;
    }

    private final Map<String, MixinAcceptorHandshakerFactory> handshakers = Maps.newLinkedHashMap();
    private void initMixinServerHandshakerFactories() {
        final ServiceLoader<MixinAcceptorHandshakerFactory> candidates = ServiceLoader.load(MixinAcceptorHandshakerFactory.class);
        for (final MixinAcceptorHandshakerFactory factory : candidates) {
            final String key = factory.name();
            if (handshakers.containsKey(key)) {
                System.err.println("A MixinAcceptorHandshakerFactory named " + key
                        + " already exists, class: " + handshakers.get(key)
                        + ". It will be overwritten.");
            }
            handshakers.put(key, factory);
            System.out.println("Loaded MixinAcceptorHandshakerFactory [" + key + "]");
        }
    }

    public MixinServerHandshaker applyHandshaker(final String type,
                                                 final SocketChannelFactory socketFactory,
                                                 final DatagramChannelFactory datagramFactory) {
        final MixinAcceptorHandshakerFactory factory = handshakers.get(type);
        if (factory == null) {
             throw new IllegalArgumentException( "Unable to find MixinAcceptorHandshakerFactory with name " + type);
        }
        return factory.createHandshaker(socketFactory, datagramFactory);
    }


    private RouteContext load(final String url, final RouteContext parent) throws IOException, ConfigurationException {
        return new ExternalServerReader(stats, factories, combiners.values(), predicates.values()).load(new URL(url), parent);
    }

}