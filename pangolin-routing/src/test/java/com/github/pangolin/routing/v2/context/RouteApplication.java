package com.github.pangolin.routing.v2.context;

import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.support.DefaultServerReader;
import com.github.pangolin.routing.v2.support.ExternalServerReader;
import com.github.pangolin.routing.v2.upstream.UpstreamServerCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamServerFactory;
import com.netflix.loadbalancer.LoadBalancerStats;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ServiceLoader;

public class RouteApplication {

    public RouteContext run() {
        return createContext();
    }

    private RouteContext createContext() {
        final ServiceLoader<RouteContextFactory> factories = ServiceLoader.load(RouteContextFactory.class);
        RouteContext parent = null;
        for (final RouteContextFactory factory : factories) {
            parent = factory.createContext(parent);
        }
        return new SimpleRouteContext(parent);
    }

    public static void main(String[] args) throws Exception {
        new RouteApplication().run();

        final ServiceLoader<UpstreamServerFactory> upstreamFactories = ServiceLoader.load(UpstreamServerFactory.class);
        final ServiceLoader<UpstreamServerCombiner> upstreamCombiners = ServiceLoader.load(UpstreamServerCombiner.class);
        final ServiceLoader<RoutePredicateFactory<InetSocketAddress, String>> predicates = (ServiceLoader) ServiceLoader.load(RoutePredicateFactory.class);
        final LoadBalancerStats stats = new LoadBalancerStats();
//        RouteContext context = new ExternalServerReader(stats, upstreamFactories, upstreamCombiners, predicates).load(new URL("http://fbapiv2.fbsublink.com/flydsubal/qcexzkf6b6w2ziwl?clash=1&extend=1"), null);
        URL url = RouteApplication.class.getResource("/conf/default.conf");
        RouteContext context = new DefaultServerReader(stats, upstreamFactories, upstreamCombiners, predicates).load(url, null);
        System.out.println(context);
        System.out.println(context.choose(InetSocketAddress.createUnresolved("8.8.8.8", 53)));
    }
}
