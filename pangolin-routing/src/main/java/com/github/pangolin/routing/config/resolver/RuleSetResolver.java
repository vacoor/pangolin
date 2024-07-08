package com.github.pangolin.routing.config.resolver;

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
public class RuleSetResolver<T> extends AbstractPrefixRuleResolver<T> {
    private static final String PATH_DIV = "/";
    private static final String PROTOCOL_FILE = "file";

    private RuleResolver<T>[] resolvers;

    public RuleSetResolver(final RuleResolver<T>... resolvers) {
        super("RULE-SET,");
        this.resolvers = resolvers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<T> doResolve(final String rule, final URL url) throws IOException {
        final URL ruleSetUrl = resolveUrl(url, rule);
        return Utils.lines(ruleSetUrl, StandardCharsets.UTF_8)
                .filter(StringUtils2::hasText)
                .map(line -> doResolveInternal(line, ruleSetUrl))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    protected List<T> doResolveInternal(final String rule, final URL url) {
        try {
            if (this.matches(rule)) {
                return this.resolve(rule, url);
            }
            for (final RuleResolver<T> resolver : resolvers) {
                if (resolver.matches(rule)) {
                    return resolver.resolve(rule, url);
                }
            }
            // XXX resolver not found.
            return Collections.emptyList();
        } catch (final IOException e) {
            log.error("Resolve error:  {} in {} -> {}", rule, url, e.getMessage(), e);
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
