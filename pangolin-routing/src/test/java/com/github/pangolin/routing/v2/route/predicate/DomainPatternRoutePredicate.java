package com.github.pangolin.routing.v2.route.predicate;

import org.springframework.util.AntPathMatcher;

import java.net.InetSocketAddress;

public class DomainPatternRoutePredicate implements RoutePredicate<InetSocketAddress> {
    private static final AntPathMatcher MATCHER = new AntPathMatcher(".");

    private final String pattern;

    public DomainPatternRoutePredicate(final String pattern) {
        this.pattern = pattern;
    }

    @Override
    public boolean test(final InetSocketAddress address) {
        final String hostname = address.isUnresolved()
                ? address.getHostString() : address.getAddress().getHostName();
        return MATCHER.match(pattern, hostname);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return pattern;
    }

}
