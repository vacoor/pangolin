package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.upstream.UpstreamServer;
import freework.util.StringUtils2;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 */
@Slf4j
public class ProxySetResolver extends AbstractPrefixRuleResolver<UpstreamServer> {
    private static final String PATH_DIV = "/";
    private static final String PROTOCOL_FILE = "file";

    private ProxyResolver resolver = new ProxyResolver();

    public ProxySetResolver() {
        super("PROXY-SET,");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<UpstreamServer> doResolve(final String proxy, final URL url) throws IOException {
        final URL proxySetUrl = resolveUrl(url, proxy);
        return Utils.lines(proxySetUrl, StandardCharsets.UTF_8)
                .filter(StringUtils2::hasText)
                .map(line -> doResolveInternal(line, proxySetUrl))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    protected List<UpstreamServer> doResolveInternal(final String line, final URL url) {
        try {
            return resolver.doResolve(line, url);
        } catch (final IOException e) {
            log.error("Resolve error:  {} in {} -> {}", line, url, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private URL resolveUrl(final URL baseUrl, final String path) {
        final String basePath = getParentPath(baseUrl.getPath());
        final String basePathToUse = basePath.endsWith(PATH_DIV) ? basePath : basePath + PATH_DIV;
        final String pathToUse = path.endsWith(PATH_DIV) ? path.substring(0, basePath.length() - 1) : path;
        final String resourcePath = basePathToUse + pathToUse;

        final String protocol = baseUrl.getProtocol();
        if (PROTOCOL_FILE.equalsIgnoreCase(protocol)) {
            return asUrl(PROTOCOL_FILE + "://" + resourcePath);
        }

        final int port = baseUrl.getPort();
        String targetBaseUrl = protocol + "://" + baseUrl.getHost();
        targetBaseUrl = port > 0 ? targetBaseUrl + ":" + port : targetBaseUrl;
        return asUrl(targetBaseUrl + resourcePath);
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
}
