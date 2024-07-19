package com.github.pangolin.routing.v2.support;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.clash.ClashConfiguration;
import com.github.pangolin.routing.v2.context.RouteContext;
import com.github.pangolin.routing.v2.context.SimpleRouteContext;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.upstream.UpstreamServerCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamServerFactory;
import com.google.common.base.Preconditions;
import com.netflix.loadbalancer.LoadBalancerStats;
import freework.net.Http;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
public class ExternalServerReader extends ReaderSupport {

    public ExternalServerReader(final LoadBalancerStats stats,
                                final Iterable<UpstreamServerFactory> factories,
                                final Iterable<UpstreamServerCombiner> combiners,
                                final Iterable<RoutePredicateFactory<InetSocketAddress, String>> predicates) {
        super(stats, factories, combiners, predicates);
    }

    public RouteContext load(final URL url, final RouteContext parent) throws IOException, ConfigurationException {
        final ClashConfiguration conf = loadClashConfiguration(url);

        final List<ClashConfiguration.ProxyDefinition> proxyDefinitions = nvl(conf.getProxies(), Collections.emptyList());
        final List<ClashConfiguration.ProxyGroupDefinition> proxyGroupDefinitions = nvl(conf.getProxyGroups(), Collections.emptyList());
        final List<String> rules = nvl(conf.getRules(), Collections.emptyList());

        final SimpleRouteContext context = new SimpleRouteContext(parent);

        proxyDefinitions.stream()
                .filter(d -> !"0.0.0.0".equals(d.getServer()))
                .map(d -> apply(d.getName(), asServerUrl(d)))
                .forEach(d -> context.addUpstream(d.getName(), d));

        proxyGroupDefinitions.stream()
                .map(g -> apply(g.getName(), g.getType(), g.getProxies(), context))
                .forEach(g -> context.addUpstream(g.getName(), g));

        rules.stream().map(r -> apply(r, url)).filter(Objects::nonNull).forEach(context::addRoute);

        return context;
    }

    private static ClashConfiguration loadClashConfiguration(final URL subscribeUrl) throws IOException {
        log.debug("Load Clash configuration from '{}'...", subscribeUrl);
        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = (HttpURLConnection) subscribeUrl.openConnection();
            httpUrlConnection.setRequestProperty("Accept", "application/json, text/plain, */*");
            httpUrlConnection.setRequestProperty("pragma", "No-Cache");
            httpUrlConnection.setRequestProperty("User-Agent", "ClashforWindows/0.19.25");
            final int responseCode = httpUrlConnection.getResponseCode();
            Preconditions.checkState(HttpURLConnection.HTTP_OK == responseCode, "responseCode = %s", responseCode);
            return ClashConfiguration.load(httpUrlConnection.getInputStream());
        } finally {
            Http.close(httpUrlConnection);
            log.debug("Load Clash configuration from '{}' completed", subscribeUrl);
        }
    }

    private String asServerUrl(final ClashConfiguration.ProxyDefinition definition) {
        final String cipher = definition.getCipher();
        final String password = definition.getPassword();
        final String userInfo = StringUtils.hasText(cipher) ? String.format("%s:%s", cipher, password) : password;
        return String.format("%s://%s@%s:%s#%s", definition.getType(), urlEncode(userInfo), definition.getServer(), definition.getPort(), urlEncode(definition.getName()));
    }

    private static String urlEncode(final String text) {
        return Http.urlEncode(text, StandardCharsets.UTF_8.name());
    }

    private static <T> T nvl(final T val, final T def) {
        return null != val ? val : def;
    }

}