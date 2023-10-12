package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.pattern.DestinationPattern;
import freework.io.IOUtils;
import io.netty.util.internal.ObjectUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 *
 */
public class RulesetResolver {
    private final PatternResolver resolver;

    public RulesetResolver(final PatternResolver resolver) {
        this.resolver = resolver;
    }

    public Set<DestinationPattern> resolveClassPathResource(final String pathInClassPath) throws IOException {
        final InputStream in = getClass().getClassLoader().getResourceAsStream(pathInClassPath);
        try {
            return null != in ? resolve(new InputStreamReader(in, StandardCharsets.UTF_8)) : Collections.emptySet();
        } finally {
            IOUtils.close(in);
        }
    }

    public Set<DestinationPattern> resolve(final Reader reader) throws IOException {
        ObjectUtil.checkNotNull(reader, "reader");
        final Set<DestinationPattern> patterns = new HashSet<>();
        final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while (null != (line = r.readLine())) {
            final int index = line.indexOf('#');
            final String lineToUse = -1 < index ? line.substring(0, index).trim() : line.trim();
            if (lineToUse.isEmpty()) {
                continue;
            }
            DestinationPattern resolve = resolver.resolve(lineToUse);
            if (null != resolve) {
                patterns.add(resolve);
            }
        }
        return patterns;
    }
}
