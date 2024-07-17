package com.github.pangolin.routing.route.predicate;

import java.net.InetAddress;

public class GeoIpRoutePredicate implements RoutePredicate<InetAddress> {
    private final String country;
    private final GeoIpRoutePredicateFactory dictionary;

    public GeoIpRoutePredicate(final String country, final GeoIpRoutePredicateFactory dictionary) {
        this.country = country;
        this.dictionary = dictionary;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(final InetAddress address) {
        final String lookupCountry = dictionary.lookupCountry(address);
        return country.equals(lookupCountry);
    }

}
