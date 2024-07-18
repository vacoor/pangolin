package com.github.pangolin.routing.v2.route.predicate;

import java.net.InetSocketAddress;

public class DomainKeywordRoutePredicateFactory implements RoutePredicateFactory<InetSocketAddress, String> {

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "DOMAIN-KEYWORD";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RoutePredicate<InetSocketAddress> apply(final String definition) {
        return new DomainKeywordRoutePredicate(definition);
    }

}
