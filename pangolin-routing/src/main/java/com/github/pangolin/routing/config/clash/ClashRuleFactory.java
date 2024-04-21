package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
import com.github.pangolin.routing.proxy.health.HealthChecker;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.routing.proxy.LoadBalanceProxyServer;
import freework.net.Http;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 *
 */
@Slf4j
public class ClashRuleFactory {

    public static Map<DestinationPattern, String> parseRules(final URL url, final PatternResolver... resolvers) throws IOException  {
        final Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8);
        final Map<DestinationPattern, String> rules = new LinkedHashMap<>();
        final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while (null != (line = r.readLine())) {
            final int index = line.indexOf('#');
            final String lineToUse = -1 < index ? line.substring(0, index).trim() : line.trim();
            if (lineToUse.isEmpty()) {
                continue;
            }

            final int i = lineToUse.lastIndexOf(",");
            if (-1 < i) {
                final String patternDefinition = lineToUse.substring(0, i);
                for (PatternResolver resolver : resolvers) {
                    List<DestinationPattern> patterns = resolver.resolve(patternDefinition, url);
                    if (null != patterns) {
                        for (DestinationPattern pattern : patterns) {
                            final String proxyType = lineToUse.substring(i + 1);
                            rules.put(pattern, proxyType);
                        }
                    }
                }
            }
        }
        return rules;
    }

    public static Map<String, ProxyServer> parseProxies(final List<Configuration.ProxyDefinition> proxyDefinitions) {
        final Map<String, ProxyServer> proxies = new HashMap<>();
        for (final Configuration.ProxyDefinition proxyDefinition : proxyDefinitions) {
            if ("0.0.0.0".equalsIgnoreCase(proxyDefinition.getServer())) {
                continue;
            }
            final String uri = String.format("%s://%s@%s:%s#%s", proxyDefinition.getType(), urlEncode(proxyDefinition.getPassword()), proxyDefinition.getServer(), proxyDefinition.getPort(), urlEncode(proxyDefinition.getName()));
            log.debug("resolve uri: {}", uri);
            final ProxyServer server = resolve(uri);
            log.debug("resolved uri: {}", uri);
            proxies.put(server.getName(), server);
        }
        return proxies;
    }

    private static String urlEncode(final String text) {
        return Http.urlEncode(text, StandardCharsets.UTF_8.name());
    }

    private static final ServiceLoader<ServerResolver> RESOLVERS = ServiceLoader.load(ServerResolver.class);

    private static ProxyServer resolve(final String url) {
        for (final ServerResolver resolver : RESOLVERS) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final ProxyServer resolved = resolver.resolve(url, new Properties());
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException();
    }

}
