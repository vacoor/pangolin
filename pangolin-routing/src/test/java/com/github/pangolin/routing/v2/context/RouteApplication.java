package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.v2.server.ServerChannelFactory;
import com.github.pangolin.routing.v2.support.DefaultServerReader;
import com.github.pangolin.routing.v2.upstream.UpstreamCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

@Slf4j
public class RouteApplication {
    private final Iterable<UpstreamFactory> upstreamFactories;
    private final Map<String, UpstreamCombiner> upstreamCombiners = Maps.newLinkedHashMap();
    private final Map<String, RoutePredicateFactory> predicateFactories = Maps.newLinkedHashMap();

    private RouteContext context;
    private List<ServerChannelFactory> connectorFactories = Lists.newLinkedList();
    private ChannelGroup connectors = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);


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

    public void addConnectors(ServerChannelFactory channelFactory) {
        connectorFactories.add(channelFactory);
    }

    public RouteContext run() throws Exception {
        context = createContext();
        for (final ServerChannelFactory factory : connectorFactories) {
            connectors.add(factory.start(this.context).channel());
        }
        return this.context;
    }

    public void await() throws InterruptedException {
        connectors.newCloseFuture().sync();
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
        return new SimpleRouteContext(parent);
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
