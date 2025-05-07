package com.github.pangolin.routing.context.spi;

import com.github.pangolin.routing.context.InheritableRouteContext;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.upstream.DirectUpstream;
import com.google.common.base.Preconditions;
import freework.net.Http;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.PropertySubstitute;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class ExternalRouteContextFactory extends AbstractRouteContextFactory {


    @Override
    public RouteContext create(final URL url, final RouteContext parent) throws Exception {
        return load(url, parent);
    }

    public RouteContext load(final URL url, final RouteContext parent) throws IOException {
        final Configuration conf = load(url);

        final List<ProxyDefinition> proxyDefinitions = nvl(conf.getProxies(), Collections.emptyList());
        final List<ProxyGroupDefinition> proxyGroupDefinitions = nvl(conf.getProxyGroups(), Collections.emptyList());
        final List<String> rules = nvl(conf.getRules(), Collections.emptyList());

        final InheritableRouteContext context = new InheritableRouteContext(parent);

        proxyDefinitions.stream()
                .filter(d -> !"0.0.0.0".equals(d.getServer()))
                .map(d -> apply(d.getName(), asServerUrl(d)))
                .forEach(d -> context.addUpstream(d.name(), d));

        for (ProxyGroupDefinition group : proxyGroupDefinitions) {
            List<String> proxiesInGroup = group.getProxies().stream()
                    .map(context::canonicalName)
                    .collect(Collectors.toList());

            proxiesInGroup.remove("DIRECT");
            if (proxiesInGroup.isEmpty()) {
                // register group name as DIRECT alias
                context.registerAlias(DirectUpstream.NAME, group.getName());
            } else if (1 == proxiesInGroup.size()) {
                // register group name as proxy alias
                context.registerAlias(proxiesInGroup.iterator().next(), group.getName());
            } else {
                context.addUpstream(group.getName(), combine(group.getName(), group.getType(), proxiesInGroup, context));
            }
        }

        rules.stream().map(r -> apply(r, url, context)).filter(Objects::nonNull).forEach(context::addRoute);

        return context;
    }


    private String asServerUrl(final ProxyDefinition definition) {
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

    private static Configuration load(final URL subscribeUrl) throws IOException {
        log.info("Load configuration from URL: '{}'...", subscribeUrl);
        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = (HttpURLConnection) subscribeUrl.openConnection();
            httpUrlConnection.setRequestProperty("Accept", "application/json, text/plain, */*");
            httpUrlConnection.setRequestProperty("pragma", "No-Cache");
            httpUrlConnection.setRequestProperty("User-Agent", "ClashforWindows/0.19.25");
            final int responseCode = httpUrlConnection.getResponseCode();
            Preconditions.checkState(HttpURLConnection.HTTP_OK == responseCode, "responseCode = %s", responseCode);
            return load(httpUrlConnection.getInputStream());
        } finally {
            Http.close(httpUrlConnection);
            log.info("Load configuration from URL: '{}' completed", subscribeUrl);
        }
    }

    private static Configuration load(final InputStream in) {
        final TypeDescription typeDescription = new TypeDescription(Configuration.class);
        typeDescription.substituteProperty(new PropertySubstitute(
                "proxy-groups", List.class,
                "getProxyGroups", "setProxyGroups", ProxyGroupDefinition.class
        ));

        final Representer representer = new Representer();
        representer.getPropertyUtils().setBeanAccess(BeanAccess.FIELD);
        representer.getPropertyUtils().setSkipMissingProperties(true);

        final Yaml yaml = new Yaml(representer);
        yaml.addTypeDescription(typeDescription);
        return yaml.loadAs(in, Configuration.class);
    }

    @Getter
    @Setter
    private static class Configuration {
        private List<ProxyDefinition> proxies;
        private List<ProxyGroupDefinition> proxyGroups;
        private List<String> rules;
    }

    @Getter
    @Setter
    private static class ProxyDefinition {
        private String name;
        private String type;
        private String server;
        private int port;
        private String cipher;
        private String password;
        private boolean udp;
    }

    @Getter
    @Setter
    private static class ProxyGroupDefinition {
        private String name;
        private String type;
        private String url;
        private List<String> proxies;
    }

}