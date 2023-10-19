package com.github.pangolin.routing.resolver;

import com.github.pangolin.routing.internal.client.Socks4ProxyHandler;
import com.github.pangolin.routing.internal.client.Socks5ProxyHandler;
import com.github.pangolin.routing.internal.client.WebSocketProxyHandler;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.routing.pattern.NetworkPattern;
import com.github.pangolin.routing.ProxyHandlerFactory;
import io.netty.util.NetUtil;
import io.netty.util.internal.ObjectUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class RoutingFileParser {

    public static Map<DestinationPattern, ProxyHandlerFactory> parse() throws IOException {
        final Reader routingReader = locateRoutingResourceAsReader();
        return null != routingReader ? parse(routingReader) : Collections.emptyMap();
    }

    public static Map<DestinationPattern, ProxyHandlerFactory> parseSilently() {
        try {
            return parse();
        } catch (final IOException e) {
            // logger.warn("Failed to load and parse hosts file at " + hostsFile.getPath(), e);
            return Collections.emptyMap();
        }
    }

    private static Reader locateRoutingResourceAsReader() throws IOException {
        String property = System.getProperty("pangolin.routing.file");
        if (null != property && !property.isEmpty()) {
            final File file = new File(property);
            if (!file.exists()) {
                throw new FileNotFoundException(file.getAbsolutePath());
            }
            final FileInputStream in = new FileInputStream(file);
            return new InputStreamReader(in, StandardCharsets.UTF_8);
        }
        final InputStream in = RoutingFileParser.class.getClassLoader().getResourceAsStream("hosts");
        return null != in ? new InputStreamReader(in, StandardCharsets.UTF_8) : null;
    }

    public static Map<DestinationPattern, ProxyHandlerFactory> parse(final Reader reader) throws IOException {
        ObjectUtil.checkNotNull(reader, "reader");
        final Map<DestinationPattern, ProxyHandlerFactory> routings = new LinkedHashMap<>();
        final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while (null != (line = r.readLine())) {
            final int index = line.indexOf('#');
            final String lineToUse = -1 < index ? line.substring(0, index).trim() : line.trim();
            if (lineToUse.isEmpty()) {
                continue;
            }
            final String[] segments = lineToUse.split("\\s+", 2);
            if (segments.length < 2) {
                continue;
            }
            routings.put(parseDestination(segments[0]), parseNextHop(segments[1]));
        }
        return routings;
    }

    private static DestinationPattern parseDestination(final String destination) {
        final String[] segments = destination.split("/", 2);
        if (segments.length == 2 && isDigit(segments[1]) && (NetUtil.isValidIpV4Address(segments[0]) || NetUtil.isValidIpV6Address(segments[0]))) {
            final int cidrPrefix = Integer.parseInt(segments[1]);
            return new NetworkPattern(segments[0], cidrPrefix);
        }
        return new DomainPattern(destination);
    }

    private static ProxyHandlerFactory parseNextHop(final String nextHop) {
        final URI uri = URI.create(nextHop);
        final String scheme = uri.getScheme();
        if ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) {
            return sa -> new WebSocketProxyHandler(uri, null);
        }
        if ("socks5".equalsIgnoreCase(scheme)) {
            return sa -> new Socks5ProxyHandler(new InetSocketAddress(uri.getHost(), uri.getPort()));
        }
        if ("socks4".equalsIgnoreCase(scheme)) {
            return sa -> new Socks4ProxyHandler(new InetSocketAddress(uri.getHost(), uri.getPort()));
        }
        throw new UnsupportedOperationException(nextHop);
    }

    private static boolean isDigit(final String text) {
        if (null != text && 0 < text.length()) {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

}
