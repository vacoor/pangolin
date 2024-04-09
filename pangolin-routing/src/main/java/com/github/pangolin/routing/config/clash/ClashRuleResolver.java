package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.routing.pattern.SubnetPattern;
import io.netty.util.NetUtil;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ClashRuleResolver implements PatternResolver {

    @Override
    public boolean isSupported(final String pattern) {
        for (final PatternResolver d : delegate) {
            if (d.isSupported(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<DestinationPattern> resolve(final String pattern, final URL url) throws IOException {
        for (final PatternResolver p : delegate) {
            if (p.isSupported(pattern)) {
                return p.resolve(pattern, url);
            }
        }
        return Collections.emptyList();
    }



    public static final PatternResolver DOMAIN = new TypedPrefixPatternResolver("DOMAIN,") {
        @Override
        protected DestinationPattern create(final String pattern) {
            return new DomainPattern(pattern);
        }
    };

    public static final PatternResolver DOMAIN_SUFFIX = new TypedPrefixPatternResolver("DOMAIN-SUFFIX,") {
        @Override
        protected DestinationPattern create(final String pattern) {
            return new DomainPattern("**." + pattern);
        }
    };

    public static final PatternResolver DOMAIN_KEYWORD = new TypedPrefixPatternResolver("DOMAIN-KEYWORD,") {

        @Override
        protected DestinationPattern create(final String pattern) {
            return new DomainPattern("**.*" + pattern + "*.**");
        }
    };

    public static final PatternResolver IP_CIDR = new TypedPrefixPatternResolver("IP-CIDR,") {
        @Override
        public boolean isSupported(final String pattern) {
            if (super.isSupported(pattern)) {
                final String[] segments = pattern.substring(prefix.length()).split("/", 2);
                return segments.length == 2 && isDigit(segments[1]) && NetUtil.isValidIpV4Address(segments[0]);
            }
            return false;
        }

        @Override
        protected DestinationPattern create(final String pattern) {
            final String[] segments = pattern.split("/", 2);
            final int cidrPrefix = Integer.parseInt(segments[1]);
            return new SubnetPattern(segments[0], cidrPrefix);
        }
    };

    public static final PatternResolver IP_CIDR_6 = new TypedPrefixPatternResolver("IP-CIDR6,") {

        @Override
        public boolean isSupported(final String pattern) {
            if (super.isSupported(pattern)) {
                final String[] segments = pattern.substring(prefix.length()).split("/", 2);
                return segments.length == 2 && isDigit(segments[1]) && NetUtil.isValidIpV6Address(segments[0]);
            }
            return false;
        }

        @Override
        protected DestinationPattern create(final String pattern) {
            final String[] segments = pattern.split("/", 2);
            final int cidrPrefix = Integer.parseInt(segments[1]);
            return new SubnetPattern(segments[0], cidrPrefix);
        }
    };

    private static abstract class TypedPrefixPatternResolver implements PatternResolver {
        protected final String prefix;

        public TypedPrefixPatternResolver(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean isSupported(final String pattern) {
            return null != pattern && pattern.startsWith(prefix) && pattern.length() > prefix.length();
        }

        @Override
        public List<DestinationPattern> resolve(final String pattern, final URL url) {
            if (isSupported(pattern)) {
                return Collections.singletonList(create(pattern.substring(prefix.length())));
            }
            return null;
        }

        protected abstract DestinationPattern create(final String pattern);
    }

    private static final PatternResolver[] delegate = {DOMAIN, DOMAIN_SUFFIX, DOMAIN_KEYWORD, IP_CIDR, IP_CIDR_6};

    public static final PatternResolver RULE_SET = new PatternResolver() {
        @Override
        public boolean isSupported(final String pattern) {
            return pattern.startsWith("RULE-SET,");
        }

        @Override
        public List<DestinationPattern> resolve(final String pattern, final URL url) throws IOException {
            final List<DestinationPattern> pp = new ArrayList<>();
            if (pattern.startsWith("RULE-SET,")) {
                final String path = pattern.substring("RULE-SET,".length());
                final String parent = getParentPath(url.getPath());
                final String absPath = path.startsWith("/") ? parent + path : parent + "/" + path;
                final String protocol = url.getProtocol();
                URL urlToUse;
                if (protocol.equalsIgnoreCase("file")) {
                    urlToUse = asUrl("file://" + absPath);
                } else {
                    final int port = url.getPort();
                    String baseUrl;
                    if (port > 0) {
                        baseUrl = protocol + "://" + url.getHost() + ":" + url.getPort();
                    } else {
                        baseUrl = protocol + "://" + url.getHost();
                    }
                    urlToUse = asUrl(baseUrl + absPath);
                }

                final Set<DestinationPattern> patterns = new RulesetResolver(delegate).resolve(new InputStreamReader(urlToUse.openStream(), StandardCharsets.UTF_8), urlToUse);
                pp.addAll(patterns);
                // new RulesetResolver().resolve()
                return pp;
            }
            return Collections.emptyList();
        }

        private URL asUrl(final String url) {
            try {
                return new URL(url);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }

        private String getParentPath(String path) {
            final int i = path.lastIndexOf("/");
            return path.substring(0, i);
        }
    };

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