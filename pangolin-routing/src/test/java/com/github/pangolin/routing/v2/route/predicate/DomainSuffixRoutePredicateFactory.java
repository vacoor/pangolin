package com.github.pangolin.routing.v2.route.predicate;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Collections;

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
    public Iterable<RoutePredicate<InetSocketAddress>> apply(final String definition, final URL location) {
        return Collections.singletonList(new DomainSuffixRoutePredicate(definition));
    }

}
