package com.github.pangolin.routing.rule.pattern;

import java.net.InetSocketAddress;

/**
 *
 */
public class DomainPattern implements DestinationPattern {
    private static final AntPathMatcher MATCHER = new AntPathMatcher(".");

    private final String pattern;

    public DomainPattern(final String pattern) {
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
