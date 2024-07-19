package com.github.pangolin.routing.v2.route.predicate.spi;

import com.github.pangolin.routing.util.SocketUtils;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicate;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public class GeoIpRoutePredicate implements RoutePredicate<InetSocketAddress> {
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
    public boolean test(final InetSocketAddress socketAddress) {
        final InetAddress address = SocketUtils.getAddress(socketAddress, false);
        final String lookupCountry = dictionary.lookupCountry(address);
        return country.equals(lookupCountry);
    }

}
