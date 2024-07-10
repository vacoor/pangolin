package com.github.pangolin.routing.context;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.ProxyGroupDefinition;
import com.github.pangolin.routing.config.RulesParser;
import com.github.pangolin.routing.config.clash.ClashConfiguration;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.google.common.base.Preconditions;
import com.netflix.loadbalancer.LoadBalancerStats;
import freework.net.Http;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class ExternalServerReader implements ServerReader {
    private final LoadBalancerStats stats;

    public ExternalServerReader(final LoadBalancerStats stats) {
        this.stats = stats;
    }

    @Override
    public ServerRegistry load(final URL url, final RouteletContext parent) throws IOException, ConfigurationException {
        final ServerRegistry registry = new ServerRegistry(parent, stats);
        final ClashConfiguration conf = loadClashConfiguration(url);

        final List<ClashConfiguration.ProxyDefinition> proxyDefinitions = nvl(conf.getProxies(), Collections.emptyList());
        final List<ProxyGroupDefinition> proxyGroupDefinitions = nvl(conf.getProxyGroups(), Collections.emptyList());
        final List<String> rules = nvl(conf.getRules(), Collections.emptyList());

        proxyDefinitions.stream()
                .filter(d -> !"0.0.0.0".equals(d.getServer()))
                .forEach(d -> registry.register(d.getName(), asServerUrl(d)));

        proxyGroupDefinitions.stream()
                .forEach(g -> registry.register(g.getName(), g.getType(), g.getProxies()));

        Map<DestinationPattern, String> ruleMap = RulesParser.parseRules(rules, url);
        ruleMap.forEach(registry::register);
        return registry;
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