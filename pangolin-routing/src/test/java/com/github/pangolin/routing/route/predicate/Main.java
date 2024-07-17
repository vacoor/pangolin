package com.github.pangolin.routing.route.predicate;

import com.google.common.collect.Maps;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public class Main {
    public static void main(String[] args) throws IOException {
        final ServiceLoader<RoutePredicateFactory> factories = ServiceLoader.load(RoutePredicateFactory.class);
        final Map<String, RoutePredicateFactory> predicates = Maps.newHashMap();
        for (final RoutePredicateFactory factory : factories) {
            final String key = factory.name();
            if (predicates.containsKey(key)) {
                System.err.println("A RoutePredicateFactory named " + key
                        + " already exists, class: " + predicates.get(key)
                        + ". It will be overwritten.");
            }
            predicates.put(key, factory);
            System.out.println("Loaded RoutePredicateFactory [" + key + "]");
        }

        final List<String> definitions = Arrays.asList(
            "IP-CIDR,192.168.1.1",
                "IP-CIDR,192.168.1.1/24",
                "GEOIP,CN"
        );
        for (final String definition : definitions) {
            final int idx = definition.indexOf(",");
            if (idx <= 0) {
                throw new IllegalStateException("Unable to parse PredicateDefinition text '"
                        + definition + "'" + ", must be of the form name,definition");
            }
            final String name = definition.substring(0, idx);
            final String arg = definition.substring(idx + 1);

            final RoutePredicateFactory factory = predicates.get(name);
            if (factory == null) {
                throw new IllegalArgumentException( "Unable to find RoutePredicateFactory with name " + name);
            }
            RoutePredicate predicate = factory.apply(arg);
            System.out.println(predicate);
        }

        /*
        GeoIpRoutePredicate cn = (GeoIpRoutePredicate) new GeoIpRoutePredicateFactory().apply("CN");
        boolean test = cn.test(SocketUtils.toSocketAddress("116.230.172.199", 22).getAddress());
        System.out.println(test);
        test = cn.test(SocketUtils.toSocketAddress("116.230.172.199", 22).getAddress());
        System.out.println(test);
        test = cn.test(SocketUtils.toSocketAddress("116.230.172.199", 22).getAddress());
        System.out.println(test);
        test = cn.test(SocketUtils.toSocketAddress("116.230.172.199", 22).getAddress());
        System.out.println(test);
        */
    }
}