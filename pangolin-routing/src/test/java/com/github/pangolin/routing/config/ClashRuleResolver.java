package com.github.pangolin.routing.config;

import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.routing.pattern.NetworkPattern;
import io.netty.util.NetUtil;

public class ClashRuleResolver implements PatternResolver {
    private PatternResolver[] delegate = {DOMAIN, DOMAIN_SUFFIX, DOMAINK_KEYWORD, IP_CIDR, IP_CIDR_6};

    @Override
    public boolean isSupported(final String pattern) {
        for (PatternResolver d : delegate) {
            if (d.isSupported(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public DestinationPattern resolve(final String pattern) {
        for (final PatternResolver p : delegate) {
            if (p.isSupported(pattern)) {
                return p.resolve(pattern);
            }
        }
        return null;
    }


    private static final PatternResolver DOMAIN = new TypedPrefixPatternResolver("DOMAIN,") {
        @Override
        protected DestinationPattern create(final String pattern) {
            return new DomainPattern(pattern);
        }
    };

    private static final PatternResolver DOMAIN_SUFFIX = new TypedPrefixPatternResolver("DOMAIN-SUFFIX,") {
        @Override
        protected DestinationPattern create(final String pattern) {
            return new DomainPattern("**." + pattern);
        }
    };

    private static final PatternResolver DOMAINK_KEYWORD = new TypedPrefixPatternResolver("DOMAIN-KEYWORD,") {

        @Override
        protected DestinationPattern create(final String pattern) {
            return new DomainPattern("**.*" + pattern + "*.**");
        }
    };

    private static final PatternResolver IP_CIDR = new TypedPrefixPatternResolver("IP-CIDR,") {
        @Override
        public boolean isSupported(final String pattern) {
            if (super.isSupported(pattern)) {
                final String[] segments = pattern.split("/", 2);
                return segments.length == 2 && isDigit(segments[1]) && NetUtil.isValidIpV4Address(segments[0]);
            }
            return false;
        }

        @Override
        protected DestinationPattern create(final String pattern) {
            final String[] segments = pattern.split("/", 2);
            final int cidrPrefix = Integer.parseInt(segments[1]);
            return new NetworkPattern(segments[0], cidrPrefix);
        }
    };

    private static final PatternResolver IP_CIDR_6 = new TypedPrefixPatternResolver("IP-CIDR6,") {

        @Override
        public boolean isSupported(final String pattern) {
            if (super.isSupported(pattern)) {
                final String[] segments = pattern.split("/", 2);
                return segments.length == 2 && isDigit(segments[1]) && NetUtil.isValidIpV6Address(segments[0]);
            }
            return false;
        }

        @Override
        protected DestinationPattern create(final String pattern) {
            final String[] segments = pattern.split("/", 2);
            final int cidrPrefix = Integer.parseInt(segments[1]);
            return new NetworkPattern(segments[0], cidrPrefix);
        }
    };

    private static abstract class TypedPrefixPatternResolver implements PatternResolver {
        private final String prefix;

        public TypedPrefixPatternResolver(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean isSupported(final String pattern) {
            return null != pattern && pattern.startsWith(prefix) && pattern.length() > prefix.length();
        }

        @Override
        public DestinationPattern resolve(final String pattern) {
            if (isSupported(pattern)) {
                return create(pattern.substring(prefix.length()));
            }
            return null;
        }

        protected abstract DestinationPattern create(final String pattern);
    }

    private static boolean isDigit(final String text) {
        if (null != text && 0 < text.length()) {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}