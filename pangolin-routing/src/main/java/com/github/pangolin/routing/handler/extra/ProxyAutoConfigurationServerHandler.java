package com.github.pangolin.routing.handler.extra;

import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.route.predicate.DomainRoutePredicate;
import com.github.pangolin.routing.route.predicate.SubnetRoutePredicate;
import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Proxy Auto-Configuration File Server Handler.
 *
 * @see <a href="https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Proxy_servers_and_tunneling/Proxy_Auto-Configuration_PAC_file">代理自动配置文件（PAC）</a>
 * @see <a href="https://github.com/manugarg/pacparser">PAC parser</a>
 */
@Slf4j
public class ProxyAutoConfigurationServerHandler extends ChannelInboundHandlerAdapter {
    private static final String DEFAULT_PAC_PATH = "/proxy.pac";
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;

    private final String pacPath;
    private final RouteRegistry routeRegistry;
    private final int proxyPort;

    public ProxyAutoConfigurationServerHandler(final RouteRegistry routeRegistry, final int proxyPort) {
        this(DEFAULT_PAC_PATH, routeRegistry, proxyPort);
    }

    public ProxyAutoConfigurationServerHandler(final String pacPath, final RouteRegistry routeRegistry, final int proxyPort) {
        this.pacPath = pacPath;
        this.routeRegistry = routeRegistry;
        this.proxyPort = proxyPort;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(HttpServerCodec.class)) {
            cp.addBefore(ctx.name(), null, new HttpServerCodec());
        }
        if (null == cp.get(HttpObjectAggregator.class)) {
            cp.addBefore(ctx.name(), null, new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null != cp.get(HttpObjectAggregator.class)) {
            cp.remove(HttpObjectAggregator.class);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        try {
            if (msg instanceof FullHttpRequest) {
                final FullHttpRequest httpRequest = (FullHttpRequest) msg;
                final QueryStringDecoder decoder = new QueryStringDecoder(httpRequest.uri());
                final String requestPath = decoder.path();
                if (requestPath.equals(this.pacPath)) {
                    final String addr = getHttpRequestAddress(httpRequest).getHostString() + ":" + proxyPort;
                    final String pac = toPac(routeRegistry.getRoutes(), addr, decoder.parameters().containsKey("http"));

                    final ByteBuf body = Unpooled.copiedBuffer(pac, StandardCharsets.UTF_8);
                    final DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK, body);
                    httpResponse.headers().add("Content-Length", body.readableBytes());
                    httpResponse.headers().add("Content-Type", "application/x-ns-proxy-autoconfig");
                    ctx.writeAndFlush(httpResponse);
                    return;
                }
            }
            ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }


    private InetSocketAddress getHttpRequestAddress(final HttpRequest httpRequest) {
        final HttpHeaders headers = httpRequest.headers();
        final String host = headers.get(HttpHeaderNames.HOST);
        if (null != host && !host.isEmpty()) {
            final String[] segments = host.split(":");
            final int port = segments.length > 1 ? Integer.parseInt(segments[1]) : determinePort(0, httpRequest.uri());
            return SocketUtils.toSocketAddress(segments[0], port);
        } else {
            final URI uri = URI.create(httpRequest.uri());
            final int port = determinePort(uri.getPort(), httpRequest.uri());
            return SocketUtils.toSocketAddress(uri.getHost(), port);
        }
    }

    private int determinePort(final int port, final String uri) {
        return port > 0 ? port : uri.toLowerCase().startsWith("https://") ? 443 : 80;
    }

    private static String toPac(final Map<RoutePredicate, String> rules, final String addr, final boolean onlyHttp) {
        final StringBuilder buff = new StringBuilder();
        final String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        buff.append("/**\r\n")
                .append(" * Proxy Auto-Configuration (PAC) file.\r\n")
                .append(" *\r\n")
                .append(" * Date: ").append(now).append("\r\n")
                .append(" * Link: https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Proxy_servers_and_tunneling/Proxy_Auto-Configuration_PAC_file\r\n")
                .append(" * Link: https://github.com/manugarg/pacparser\r\n")
                .append(" */\r\n");

        buff.append("function FindProxyForURL(url, host) {\r\n");

        buff.append("  ").append("var $PROXY = '");
        if (onlyHttp) {
            buff.append(String.format("PROXY %s", addr));
        } else {
            buff.append(String.format("SOCKS5 %s; SOCKS %s; PROXY %s", addr, addr, addr));
        }
        buff.append("';\r\n");
        for (final Map.Entry<RoutePredicate, String> entry : rules.entrySet()) {
            if (!"DIRECT".equalsIgnoreCase(entry.getValue())) {
                RoutePredicate predicate = entry.getKey();
                String s = toPacStatement(predicate);
                buff.append("  ").append(s).append("\r\n");
            }
        }
        buff.append("  if (!isResolvable(host)) return $PROXY + '; DIRECT';\r\n");
        buff.append("  return 'DIRECT';\r\n");
        buff.append("}");
        log.debug(buff.toString());
        return buff.toString();
    }

    private static String toPacStatement(final RoutePredicate pattern) {
        if (pattern instanceof DomainRoutePredicate) {
            final String prefixWildcard = "**.";
            final String suffixWildcard = ".**";
            final DomainRoutePredicate dp = (DomainRoutePredicate) pattern;
            String s1 = dp.toString();
            final boolean isPrefixWildcard = s1.startsWith(prefixWildcard);
            final boolean isSuffixWildcard = s1.endsWith(suffixWildcard);
            if (isPrefixWildcard && isSuffixWildcard) {
                s1 = s1.replace("**.", "").replace(".**", "");
                if (s1.startsWith("*") && s1.endsWith("*")) {
                    return String.format("if (shExpMatch(host, '%s')) return $PROXY;", s1);
                } else {
                    System.out.println("Unsupported");
                }
            } else if (isPrefixWildcard) {
                s1 = s1.replace("**.", "");
                return String.format("if (dnsDomainIs(host, '.%s')) return $PROXY;", s1);
            } else {
                return String.format("if (shExpMatch(host, '%s')) return $PROXY;", s1);
            }
        } else if (pattern instanceof SubnetRoutePredicate) {
            final SubnetRoutePredicate p = (SubnetRoutePredicate) pattern;
            RoutePredicate delegate = p.getDelegate();
            if (delegate instanceof SubnetRoutePredicate.Inet4SubnetPattern) {
                SubnetRoutePredicate.Inet4SubnetPattern i4sn = (SubnetRoutePredicate.Inet4SubnetPattern) delegate;
                String networkAddress = i4sn.getNetworkAddress();
                String subnetMask = i4sn.getSubnetMask();
                return String.format("if (isInNet(host, '%s', '%s')) return $PROXY;", networkAddress, subnetMask);
            }
        }
        return String.format("/* NOT SUPPORTED: %s */", pattern);
    }
}