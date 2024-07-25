package com.github.pangolin.routing.route.predicate.spi;

import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.util.SocketUtils;

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

    @Override
    public String toString() {
        return "GEOIP," + country;
    }
}
