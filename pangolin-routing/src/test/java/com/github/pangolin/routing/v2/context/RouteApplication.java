package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.v2.server.Acceptor;
import com.github.pangolin.routing.v2.support.DefaultServerReader;
import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

@Slf4j
public class RouteApplication {
    private final Iterable<UpstreamFactory> upstreamFactories;
    private final Map<String, UpstreamCombiner> upstreamCombiners = Maps.newLinkedHashMap();
    private final Map<String, RoutePredicateFactory> predicateFactories = Maps.newLinkedHashMap();

    private RouteContext context;
    private final List<Acceptor> acceptors = Lists.newLinkedList();
    private final ChannelGroup channelGroup = new DefaultChannelGroup("acceptor-channels", GlobalEventExecutor.INSTANCE);


    public RouteApplication() {
        this(
                ServiceLoader.load(UpstreamFactory.class),
                ServiceLoader.load(UpstreamCombiner.class),
                ServiceLoader.load(RoutePredicateFactory.class)
        );
    }

    public RouteApplication(final Iterable<UpstreamFactory> upstreamFactories,
                            final Iterable<UpstreamCombiner> upstreamCombiners,
                            final Iterable<RoutePredicateFactory> predicateFactories) {
        this.upstreamFactories = upstreamFactories;
        this.initUpstreamCombiners(upstreamCombiners);
        this.initPredicateFactories(predicateFactories);
    }

    private void initUpstreamCombiners(final Iterable<UpstreamCombiner> factories) {
        for (final UpstreamCombiner factory : factories) {
            final String key = factory.name();
            if (upstreamCombiners.containsKey(key)) {
                log.warn("A UpstreamCombiner named " + key + " already exists, class: " + upstreamCombiners.get(key) + ". It will be overwritten.");
            }
            upstreamCombiners.put(key, factory);
            log.info("Loaded UpstreamCombiner [" + key + "]");
        }
    }

    private void initPredicateFactories(final Iterable<RoutePredicateFactory> factories) {
        for (final RoutePredicateFactory factory : factories) {
            final String key = factory.name();
            if (predicateFactories.containsKey(key)) {
                log.warn("A RoutePredicateFactory named " + key + " already exists, class: " + predicateFactories.get(key) + ". It will be overwritten.");
            }
            predicateFactories.put(key, factory);
            log.info("Loaded RoutePredicateFactory [" + key + "]");
        }
        predicateFactories.put("RULE-SET", new RoutePredicateSetFactory("RULE-SET", factories));
    }

    public Iterable<UpstreamFactory> getUpstreamFactories() {
        return upstreamFactories;
    }

    public UpstreamCombiner getUpstreamCombiners(final String name) {
        return upstreamCombiners.get(name);
    }

    public <T, D> RoutePredicateFactory<T, D> getRoutePredicateFactory(final String name) {
        return predicateFactories.get(name);
    }

    public RouteContext getContext() {
        return this.context;
    }

    public void setContext(final RouteContext context) {
        this.context = context;
    }

    public void addAcceptors(final Acceptor... acceptors) {
        this.acceptors.addAll(Arrays.asList(acceptors));
    }


    public RouteContext run() throws Exception {
        context = createContext();

        for (final Acceptor acceptor : acceptors) {
            final ChannelFuture start = acceptor.start(context);
            final Channel channel = start.channel();
            channelGroup.add(channel);
            start.sync();
            final SocketAddress bound = channel.localAddress();

            log.info("bound to {}", bound);
        }

        return this.context;
    }


    public void await() throws InterruptedException {
        channelGroup.newCloseFuture().sync();
    }


    private RouteContext createContext() throws Exception {
        final ServiceLoader<RouteContextFactory> factories = ServiceLoader.load(RouteContextFactory.class);
        RouteContext parent = null;
        for (final RouteContextFactory factory : factories) {
            parent = factory.createContext(parent);
            if (parent instanceof ApplicationInitializer) {
                ((ApplicationInitializer) parent).initialize(this);
            }
        }
        return new DefaultRouteContext(parent);
    }

    public static void main(String[] args) throws Exception {
        RouteApplication app = new RouteApplication();

        final LoadBalancerStats stats = new LoadBalancerStats();

        final ServiceLoader<UpstreamFactory> upstreamFactories = ServiceLoader.load(UpstreamFactory.class);
        final ServiceLoader<UpstreamCombiner> upstreamCombiners = ServiceLoader.load(UpstreamCombiner.class);
        final ServiceLoader<RoutePredicateFactory<InetSocketAddress, String>> predicates = (ServiceLoader) ServiceLoader.load(RoutePredicateFactory.class);

//        RouteContext context = new ExternalServerReader(stats, upstreamFactories, upstreamCombiners, predicates).load(new URL("http://fbapiv2.fbsublink.com/flydsubal/qcexzkf6b6w2ziwl?clash=1&extend=1"), null);
        final URL configLocation = RouteApplication.class.getResource("/conf/default.conf");
        final RouteContext context = new DefaultServerReader(stats, upstreamFactories, upstreamCombiners, predicates).load(configLocation, null);

        System.out.println(context);
        System.out.println(context.choose(InetSocketAddress.createUnresolved("8.8.8.8", 53)));
        app.run();
        app.await();
    }
}
