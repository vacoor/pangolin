package com.github.pangolin.routing.v2.route.predicate;

public class DomainKeywordRoutePredicate extends DomainPatternRoutePredicate {

    public DomainKeywordRoutePredicate(final String domain) {
        super("**.*" + domain + "*.**");
    }

}
