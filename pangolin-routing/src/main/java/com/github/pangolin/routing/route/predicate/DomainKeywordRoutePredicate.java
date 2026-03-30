package com.github.pangolin.routing.route.predicate;

public class DomainKeywordRoutePredicate extends DomainPatternRoutePredicate {

    public DomainKeywordRoutePredicate(final String domain) {
        super("**.*" + checkNotBlank(domain, "Domain is blank") + "*.**");
    }

}
