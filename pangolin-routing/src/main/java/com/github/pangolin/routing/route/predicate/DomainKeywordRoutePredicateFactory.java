package com.github.pangolin.routing.route.predicate;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Collections;

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
    public Iterable<RoutePredicate<InetSocketAddress>> apply(final String definition, final URL location) {
        return Collections.singletonList(new DomainKeywordRoutePredicate(definition));
    }

}
