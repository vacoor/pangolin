package com.github.pangolin.routing.internal.server;

import io.netty.util.internal.ObjectUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Set;
import java.util.TreeSet;

/**
 *
 */
public class RulePatternParser {

    public Set<String> parse(final Reader reader) throws IOException {
        ObjectUtil.checkNotNull(reader, "reader");
        final Set<String> patterns = new TreeSet<>();
        final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while (null != (line = r.readLine())) {
            final int index = line.indexOf('#');
            final String lineToUse = -1 < index ? line.substring(0, index).trim() : line.trim();
            if (lineToUse.isEmpty()) {
                continue;
            }
            final String pattern = parse(lineToUse + ",PROXY");
            if (null != pattern) {
                patterns.add(pattern);
            }
        }
        return patterns;
    }

    public String parse(final String rule) {
        final String[] segments = rule.split(",");
        final String type = segments[0];
        if ("DOMAIN".equalsIgnoreCase(type)) {
            final String destination = segments[1];
            return destination;
        } else if ("DOMAIN-SUFFIX".equalsIgnoreCase(type)) {
            final String destination = segments[1];
            return "**." + destination;
        } else if ("DOMAIN-KEYWORD".equalsIgnoreCase(type)) {
            final String destination = segments[1];
            return "**.*" + destination + "*.**";
        } else if ("IP-CIDR".equalsIgnoreCase(type) || "IP-CIDR6".equalsIgnoreCase(type)) {
            final String destination = segments[1];
            return destination;
        } else {
            System.err.println("UNSUPPORTED: " + rule);
        }
        return null;
    }

}
