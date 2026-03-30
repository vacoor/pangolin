package com.github.pangolin.routing.route.predicate;

public class DomainSuffixRoutePredicate extends DomainPatternRoutePredicate {

    public DomainSuffixRoutePredicate(final String domain) {
        super("**." + checkNotBlank(domain, "Domain is blank"));
    }


}
