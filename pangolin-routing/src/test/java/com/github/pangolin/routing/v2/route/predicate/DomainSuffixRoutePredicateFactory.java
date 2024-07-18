package com.github.pangolin.routing.v2.route.predicate;

import java.net.InetSocketAddress;

public class DomainSuffixRoutePredicateFactory implements RoutePredicateFactory<InetSocketAddress, String> {

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "DOMAIN-SUFFIX";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RoutePredicate<InetSocketAddress> apply(final String definition) {
        return new DomainSuffixRoutePredicate(definition);
    }

}
