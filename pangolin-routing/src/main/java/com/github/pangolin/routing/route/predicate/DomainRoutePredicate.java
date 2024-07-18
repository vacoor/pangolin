package com.github.pangolin.routing.route.predicate;

import java.net.InetSocketAddress;

/**
 *
 */
public class DomainRoutePredicate implements RoutePredicate {
    private static final AntPathMatcher MATCHER = new AntPathMatcher(".");

    private final String pattern;

    public DomainRoutePredicate(final String pattern) {
        this.pattern = pattern;
    }

    @Override
    public boolean matches(final InetSocketAddress destination) {
        if (destination.isUnresolved()) {
            return MATCHER.matches(pattern, destination.getHostString());
        } else {
            final String hostname = destination.getAddress().getHostName();
            return MATCHER.matches(pattern, hostname);
        }
    }

    @Override
    public String toString() {
        return pattern;
    }
}
