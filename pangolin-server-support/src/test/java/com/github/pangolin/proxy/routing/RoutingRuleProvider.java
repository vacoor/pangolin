package com.github.pangolin.proxy.routing;

import com.github.pangolin.proxy.client.Socks4ProxyHandler;
import com.github.pangolin.proxy.client.Socks5ProxyHandler;
import com.github.pangolin.proxy.client.WebSocketProxyHandler;
import com.github.pangolin.proxy.routing.pattern.Condition;
import com.github.pangolin.proxy.routing.pattern.DomainPattern;
import com.github.pangolin.proxy.routing.pattern.InetSubnetCondition;
import io.netty.channel.ChannelHandler;
import io.netty.util.NetUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

public class RoutingRuleProvider {

    public static List<RoutingRule> loadRoutingRules() throws IOException {
        final InputStream hosts = RoutingRuleProvider.class.getClassLoader().getResourceAsStream("hosts");
        if (null != hosts) {
            return parse(new InputStreamReader(hosts, StandardCharsets.UTF_8));
        }
        return Collections.emptyList();
    }

    private static List<RoutingRule> parse(final Reader reader) throws IOException {
        final List<RoutingRule> routings = new LinkedList<RoutingRule>();
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
            routings.add(new DefaultRoutingRule(parseDestination(segments[0]), parseNextHop(segments[1])));
        }
        return routings;
    }

    private static Condition<InetSocketAddress> parseDestination(final String destination) {
        final String[] segments = destination.split("/", 2);
        if (segments.length == 2 && isDigit(segments[1]) && (NetUtil.isValidIpV4Address(segments[0]) || NetUtil.isValidIpV6Address(segments[0]))) {
            final int cidrPrefix = Integer.parseInt(segments[1]);
            return new InetSubnetCondition(segments[0], cidrPrefix);
        }
        return new DomainPattern(destination);
    }

    private static Supplier<ChannelHandler> parseNextHop(final String nextHop) {
        final URI uri = URI.create(nextHop);
        final String scheme = uri.getScheme();
        if ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) {
            return () -> new WebSocketProxyHandler(uri, "CONNECT");
        }
        if ("socks5".equalsIgnoreCase(scheme)) {
            return () -> new Socks5ProxyHandler(new InetSocketAddress(uri.getHost(), uri.getPort()));
        }
        if ("socks4".equalsIgnoreCase(scheme)) {
            return () -> new Socks4ProxyHandler(new InetSocketAddress(uri.getHost(), uri.getPort()));
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
