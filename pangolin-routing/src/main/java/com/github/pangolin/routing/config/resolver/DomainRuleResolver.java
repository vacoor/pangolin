package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.routing.rule.pattern.DomainPattern;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class DomainRuleResolver extends AbstractPrefixRuleResolver<DestinationPattern> {

    public DomainRuleResolver() {
        super("DOMAIN,");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<DestinationPattern> doResolve(final String rule, final URL url) throws IOException {
        return Collections.singletonList(
                new DomainPattern(rule)
        );
    }

}
