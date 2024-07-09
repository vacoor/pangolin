package com.github.pangolin.routing.config.resolver;

import java.io.IOException;
import java.net.URL;
import java.util.List;

/**
 *
 */
public abstract class AbstractPrefixRuleResolver<T> implements Resolver<T> {
    protected final String prefix;

    public AbstractPrefixRuleResolver(final String prefix) {
        this.prefix = prefix;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean matches(final String rule) {
        return null != rule && rule.startsWith(prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<T> resolve(final String rule, final URL url) throws IOException {
        return matches(rule) ? doResolve(rule.substring(prefix.length()), url) : null;
    }

    protected abstract List<T> doResolve(final String rule, final URL url) throws IOException;

}
