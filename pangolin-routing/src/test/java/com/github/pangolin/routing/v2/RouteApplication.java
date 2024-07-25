package com.github.pangolin.routing.v2;

import com.github.pangolin.routing.v2.context.*;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.v2.server.Acceptor;
import com.github.pangolin.routing.v2.server.AcceptorProvider;
import com.github.pangolin.routing.v2.stats.StatsAware;
import com.github.pangolin.routing.v2.stats.StatsUpstreamCombiner;
import com.github.pangolin.routing.v2.stats.StatsUpstreamFactory;
import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamFactory;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

@Slf4j
public class RouteApplication {
    private final LoadBalancerStats stats = new LoadBalancerStats();
    private final Iterable<UpstreamFactory> upstreamFactories;
    private final Map<String, UpstreamCombiner> upstreamCombiners = Maps.newLinkedHashMap();
    private final Map<String, RoutePredicateFactory> predicateFactories = Maps.newLinkedHashMap();

    //    private final List<Acceptor> acceptors = Lists.newLinkedList();
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
        this.upstreamFactories = this.initUpstreamFactories(upstreamFactories);
        this.initUpstreamCombiners(upstreamCombiners);
        this.initPredicateFactories(predicateFactories);
    }

    private Iterable<UpstreamFactory> initUpstreamFactories(final Iterable<UpstreamFactory> factories) {
        return Iterables.transform(factories, new Function<UpstreamFactory, UpstreamFactory>() {
            @Override
            public UpstreamFactory apply(final UpstreamFactory upstreamFactory) {
                return new StatsUpstreamFactory(upstreamFactory, stats);
            }
        });
    }

    private void initUpstreamCombiners(final Iterable<UpstreamCombiner> factories) {
        for (final UpstreamCombiner factory : factories) {
            final String key = factory.name();
            if (upstreamCombiners.containsKey(key)) {
                log.warn("A UpstreamCombiner named " + key + " already exists, class: " + upstreamCombiners.get(key) + ". It will be overwritten.");
            }
            if (factory instanceof StatsAware) {
                ((StatsAware) factory).setStats(stats);
            }
            upstreamCombiners.put(key, new StatsUpstreamCombiner(factory, stats));
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
        predicateFactories.put("RULE-SET", new RoutePredicateSetFactory("RULE-SET", predicateFactories));
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


    public RouteContext run(final URL configLocation) throws Exception {
        RouteContext context = createContext(configLocation);

        for (RouteContext ctx = context; null != ctx; ctx = ctx.parent()) {
            if (!(ctx instanceof AcceptorProvider)) {
                continue;
            }
            final List<Acceptor> acceptors = ((AcceptorProvider) ctx).getAcceptors();
            for (final Acceptor acceptor : acceptors) {
                final ChannelFuture start = acceptor.start(context);
                final Channel channel = start.channel();
                channelGroup.add(channel);
                start.sync();
                final SocketAddress bound = channel.localAddress();

                log.info("bound to {}", bound);
            }
        }

        return context;
    }


    public void await() throws InterruptedException {
        channelGroup.newCloseFuture().sync();
    }


    private RouteContext createContext(final URL configLocation) throws Exception {
        final ServiceLoader<RouteContextFactory> factories = ServiceLoader.load(RouteContextFactory.class);
        RouteContext context = null;
        for (final RouteContextFactory factory : factories) {
            if (factory instanceof UpstreamFactoriesAware) {
                ((UpstreamFactoriesAware) factory).setUpstreamFactories(upstreamFactories);
            }
            if (factory instanceof UpstreamCombinersAware) {
                ((UpstreamCombinersAware) factory).setUpstreamCombiners(upstreamCombiners);
            }
            if (factory instanceof RoutePredicateFactoriesAware) {
                ((RoutePredicateFactoriesAware) factory).setRoutePredicateFactories(predicateFactories);
            }

            context = factory.createContext(configLocation, context);
        }

        return null != context ? context : new InMemoryRouteContext();
        /*
        final LoadBalancerStats stats = new LoadBalancerStats();

        final ServiceLoader<UpstreamFactory> upstreamFactories = ServiceLoader.load(UpstreamFactory.class);
        final ServiceLoader<UpstreamCombiner> upstreamCombiners = ServiceLoader.load(UpstreamCombiner.class);
        final ServiceLoader<RoutePredicateFactory<InetSocketAddress, String>> predicates = (ServiceLoader) ServiceLoader.load(RoutePredicateFactory.class);

//        RouteContext context = new ExternalRouteContextFactory(stats, upstreamFactories, upstreamCombiners, predicates).load(new URL("http://fbapiv2.fbsublink.com/flydsubal/qcexzkf6b6w2ziwl?clash=1&extend=1"), null);
        final URL configLocation = RouteApplication.class.getResource("/conf/default.conf");
        return new DefaultRouteContextFactory(stats, upstreamFactories, upstreamCombiners, predicates).load(configLocation, null);
        */
    }

    public static void main(String[] args) throws Exception {
        RouteApplication app = new RouteApplication();
        RouteContext run = app.run(RouteApplication.class.getResource("/conf/default.conf"));
        app.await();
    }
}
