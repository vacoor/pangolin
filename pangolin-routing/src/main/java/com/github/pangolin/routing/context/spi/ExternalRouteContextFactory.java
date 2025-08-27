package com.github.pangolin.routing.context.spi;

import com.github.pangolin.routing.context.InheritableRouteContext;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.upstream.DirectUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamFactory;
import com.google.common.base.Preconditions;
import freework.net.Http;
import freework.util.StringUtils2;
import freework.util.Throwables;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.PropertySubstitute;
import org.yaml.snakeyaml.representer.Representer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class ExternalRouteContextFactory extends AbstractRouteContextFactory {


    @Override
    public RouteContext create(final URL url, final RouteContext parent) throws Exception {
        return load(url, parent);
    }

    public RouteContext load(final URL url, final RouteContext parent) throws IOException {
        final Map<String, String> params = Http.splitQuery(url.getQuery(), StandardCharsets.UTF_8.name());
        final String excludeProxiesStr = params.get("exclude-proxies");
        final List<String> excludeProxies = StringUtils2.hasText(excludeProxiesStr)
                ? Arrays.asList(excludeProxiesStr.split("\\s*[,;]\\s*"))
                : Collections.emptyList();

        final Configuration conf = load(url);

        final List<ProxyDefinition> proxyDefinitions = nvl(conf.getProxies(), Collections.emptyList());
        final List<ProxyGroupDefinition> proxyGroupDefinitions = nvl(conf.getProxyGroups(), Collections.emptyList());
        final List<String> rules = nvl(conf.getRules(), Collections.emptyList());

        final InheritableRouteContext context = new InheritableRouteContext(parent);

        proxyDefinitions.stream()
                .filter(d -> !"0.0.0.0".equals(d.getServer()))
                .filter(d -> !excludeProxies.contains(d.getName()))
                .map(d -> applyQuiet(d.getName(), asServerUrl(d)))
                .filter(Objects::nonNull)
                .forEach(d -> context.addUpstream(d.name(), d));

        for (ProxyGroupDefinition group : proxyGroupDefinitions) {
            if (excludeProxies.contains(group.getName())) {
                continue;
            }
            final List<String> proxiesInGroup = group.getProxies().stream()
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
                final Upstream combine = combine(group.getName(), group.getType(), proxiesInGroup, context);
                if (null != combine) {
                    context.addUpstream(group.getName(), combine);
                } else {
                    log.warn("Unable upstream combine type {}", group.getType());
                }
            }
        }

        rules.stream().map(r -> apply(r, url, context)).filter(Objects::nonNull).forEach(context::addRoute);

        return context;
    }

    private Upstream applyQuiet(final String name, final String url) {
        try {
            return apply(name, url);
        } catch (final IllegalStateException ex) {
            if (Throwables.causedBy(ex, UnknownHostException.class)) {
                log.warn("UnknownHost: {}", url, ex);
                return null;
            }
            throw ex;
        }
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

            final String responseBody = Http.getResponseBodyAsString(httpUrlConnection);
            return load(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
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

    public static void main(String[] args) throws Exception {

        String url = "https://api.eehk.net/api/v1/client/subscribe?token=7516167358ce5c1c313a9c30cc202ac4&exclude-proxies=%F0%9F%8F%B7%E5%AE%98%E7%BD%91%EF%BC%9Aeevpn.com,%F0%9F%8F%B7%E5%9B%BD%E5%86%85%E5%85%8D%E7%BF%BB%EF%BC%9A55jiasu.com,%F0%9F%8F%B7%E9%82%80%E8%AF%B7%E6%9C%8B%E5%8F%8B%E8%8E%B7%E5%BE%9720%25%E4%BD%A3%E9%87%91%E5%A5%96%E5%8A%B1";

        ExternalRouteContextFactory factory = new ExternalRouteContextFactory();
        factory.setUpstreamFactories(ServiceLoader.load(UpstreamFactory.class));
        RouteContext routeContext = factory.create(new URL(url), null);
        System.out.println(routeContext);
    }
}