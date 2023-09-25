package com.github.pangolin.routing.pattern;

/*
import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import com.maxmind.db.Reader;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class GeoIpPattern implements DestinationPattern {
    private final Reader geoReader;
    private final String country;

    public GeoIpPattern(final Reader geoReader, final String country) {
        this.geoReader = geoReader;
        this.country = country;
    }

    @Override
    public boolean matches(final InetSocketAddress destination) {
        InetAddress address = destination.getAddress();
        try {
            final GeoData geoData = geoReader.get(address, GeoData.class);
            if (null != geoData) {
                return country.equalsIgnoreCase(geoData.country.isoCode);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
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
*/