package com.github.pangolin.routing.v2.route.predicate;

public class DomainSuffixRoutePredicate extends DomainPatternRoutePredicate {

    public DomainSuffixRoutePredicate(final String domain) {
        super("**." + domain);
    }

}
