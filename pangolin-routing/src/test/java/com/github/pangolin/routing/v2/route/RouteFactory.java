package com.github.pangolin.routing.v2.route;

import com.github.pangolin.routing.v2.route.predicate.RoutePredicate;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateDefinition;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.ServiceLoader;

/**
 */
@Slf4j
public class RouteFactory {
    private final Map<String, RoutePredicateFactory> predicates = Maps.newLinkedHashMap();

    public RouteFactory() {
        this(ServiceLoader.load(RoutePredicateFactory.class));
    }

    public RouteFactory(final Iterable<RoutePredicateFactory> predicates) {
        predicates.forEach(factory -> {
            final String key = factory.name();
            if (this.predicates.containsKey(key)) {
                log.warn("A RoutePredicateFactory named " + key
                        + " already exists, class: " + this.predicates.get(key)
                        + ". It will be overwritten.");
            }
            this.predicates.put(key, factory);
            log.info("Loaded RoutePredicateFactory [" + key + "]");
        });
    }

    RoutePredicate<?> apply(final RoutePredicateDefinition predicate) {
        final String name = predicate.name();
        final RoutePredicateFactory factory = predicates.get(name);
        if (factory == null) {
            throw new IllegalArgumentException( "Unable to find RoutePredicateFactory with name " + name);
        }
        return factory.apply(predicate);
    }

}
