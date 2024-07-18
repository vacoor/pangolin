package com.github.pangolin.routing.v2.route.predicate.spi;

import com.github.pangolin.routing.v2.route.predicate.RoutePredicate;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.maxmind.db.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.zip.GZIPInputStream;

public class GeoIpRoutePredicateFactory implements RoutePredicateFactory<InetAddress, String> {
    private static final String DEFAULT_DB_PATH = "/Country.mmdb.gz";
    private static final NodeCache DEFAULT_CACHE = new CHMCache();

    /**
     * {@inheritDoc}
     */
    public String name() {
        return "GEOIP";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RoutePredicate<InetAddress> apply(final String definition) {
        return new GeoIpRoutePredicate(definition, this);
    }

    public String lookupCountry(final InetAddress address) {
        if (null == address) {
            return null;
        }
        final Reader defaultReader = openDefaultReader();
        try {
            return lookupCountry(defaultReader, address);
        } finally {
            closeQuiet(defaultReader);
        }
    }

    private String lookupCountry(final Reader reader, final InetAddress address) {
        try {
            final GeoData geoData = reader.get(address, GeoData.class);
            return null != geoData ? geoData.country.isoCode : null;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Reader openDefaultReader() {
        try {
            // TODO close.
            return new Reader(
                    new GZIPInputStream(GeoIpRoutePredicateFactory.class.getResourceAsStream(DEFAULT_DB_PATH)),
                    DEFAULT_CACHE
            );
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuiet(final Closeable closeable) {
        try {
            if (null != closeable) {
                closeable.close();
            }
        } catch (final IOException e) {
            // ignore
        }
    }

    public static class GeoData {
        public static class Country {
            private final String isoCode;

            @MaxMindDbConstructor
            public Country(@MaxMindDbParameter(name = "iso_code") final String isoCode) {
                this.isoCode = isoCode;
            }
        }

        private final Country country;

        @MaxMindDbConstructor
        public GeoData(@MaxMindDbParameter(name = "country") final Country country) {
            this.country = country;
        }
    }

}