package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
import freework.net.Http;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 *
 */
@Slf4j
public class ClashProxiesParser {

    public static Map<String, ProxyServer> parseProxies(final List<ClashConfiguration.ProxyDefinition> proxyDefinitions) {
        final Map<String, ProxyServer> proxies = new HashMap<>();
        for (final ClashConfiguration.ProxyDefinition proxyDefinition : proxyDefinitions) {
            if ("0.0.0.0".equalsIgnoreCase(proxyDefinition.getServer())) {
                continue;
            }

            final String cipher = proxyDefinition.getCipher();
            final String password = proxyDefinition.getPassword();
            final String userInfo = StringUtils.hasText(cipher) ? String.format("%s:%s", cipher, password) : password;

            final String uri = String.format("%s://%s@%s:%s#%s", proxyDefinition.getType(), urlEncode(userInfo), proxyDefinition.getServer(), proxyDefinition.getPort(), urlEncode(proxyDefinition.getName()));
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
