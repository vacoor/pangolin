package com.github.pangolin.routing.route.predicate;

public class DomainRoutePredicate extends DomainPatternRoutePredicate {

    public DomainRoutePredicate(final String domain) {
        super(checkNotBlank(domain, "Domain is blank"));
    }

}
