package com.github.pangolin.routing.config;

import com.github.pangolin.routing.pattern.DestinationPattern;
import freework.io.IOUtils;
import io.netty.util.internal.ObjectUtil;
import org.yaml.snakeyaml.reader.UnicodeReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 *
 */
public class RulesetResolver {
    public final PatternResolver resolver;

    public RulesetResolver(final PatternResolver resolver) {
        this.resolver = resolver;
    }

    public Set<DestinationPattern> parseClassPathResource(final String pathInClassPath) throws IOException {
        final InputStream in = getClass().getClassLoader().getResourceAsStream(pathInClassPath);
        try {
            return null != in ? parse(new InputStreamReader(in, StandardCharsets.UTF_8)) : Collections.emptySet();
        } finally {
            IOUtils.close(in);
        }
    }

    public Set<DestinationPattern> parse(final Reader reader) throws IOException {
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
