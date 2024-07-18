package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.route.predicate.DomainRoutePredicate;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class DomainSuffixRuleResolver extends AbstractPrefixRuleResolver<RoutePredicate> {

    public DomainSuffixRuleResolver() {
        super("DOMAIN-SUFFIX,");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<RoutePredicate> doResolve(final String rule, final URL url) throws IOException {
        return Collections.singletonList(
                new DomainRoutePredicate("**." + rule)
        );
    }

}
