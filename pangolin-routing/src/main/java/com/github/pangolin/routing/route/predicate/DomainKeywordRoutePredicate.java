package com.github.pangolin.routing.route.predicate;

public class DomainKeywordRoutePredicate extends DomainPatternRoutePredicate {

    public DomainKeywordRoutePredicate(final String domain) {
        super("**.*" + domain + "*.**");
    }

}
