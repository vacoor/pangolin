package com.github.pangolin.routing.handler.internal.server;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Http proxy server handler.
 */
@Slf4j
public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final String NONE = "";
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;

    private final String username;
    private final String password;
    private final String authorization;

    private final SocketChannelFactory factory;

    public HttpProxyServerHandler() {
        this(null, null);
    }

    public HttpProxyServerHandler(final String username, final String password) {
        this(username, password, new StandardSocketChannelFactory(null));
    }

    public HttpProxyServerHandler(final String username, final String password, final SocketChannelFactory factory) {
        this.username = username;
        this.password = password;
        this.authorization = encode(username, password);
        this.factory = factory;
    }

    private String encode(final String username, final String password) {
        if (null != username || null != password) {
            final String usernameToUse = null != username ? username : NONE;
            final String passwordToUse = null != password ? password : NONE;
            final ByteBuf buf = Unpooled.copiedBuffer(usernameToUse + ':' + passwordToUse, CharsetUtil.UTF_8);
            final ByteBuf encoded = Base64.encode(buf, false);
            final String encodedStr = "Basic " + encoded.toString(CharsetUtil.US_ASCII);
            buf.release();
            encoded.release();
            return encodedStr;
        }
        return null;
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
            if (!(msg instanceof FullHttpRequest) || !((FullHttpRequest) msg).decoderResult().isSuccess()) {
                log.warn("[HTTP] Connection closed by UNKNOWN message '{}'", msg.getClass());
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            final FullHttpRequest httpRequest = (FullHttpRequest) msg;
            final HttpMethod method = httpRequest.method();
            final SocketAddress clientAddress = ctx.channel().remoteAddress();

            if (!this.authenticate(httpRequest)) {
                log.warn("[HTTP] Respond not permitted to {}", clientAddress);
                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (HttpMethod.CONNECT.equals(method)) {
                /*-
                 * CONNECT uses a special form of request target, unique to this method,
                 * consisting of only the host and port number of the tunnel destination,
                 * separated by a colon. There is no default port; a client MUST send the
                 * port number even if the CONNECT request is based on a URI reference
                 * that contains an authority component with an elided port (Section 4.1).
                 * For example,
                 * <pre>
                 * CONNECT server.example.com:80 HTTP/1.1
                 * Host: server.example.com
                 * </pre>
                 *
                 * A server MUST reject a CONNECT request that targets an empty or invalid port number,
                 * typically by responding with a 400 (Bad Request) status code
                 *
                 * @see https://www.rfc-editor.org/rfc/rfc9110.html#name-connect
                 */
                log.info("[HTTP] Received {} HTTPS CONNECT request => {}", clientAddress, httpRequest.uri());

                final Matcher matcher = CONNECT_URI_PATTERN.matcher(httpRequest.uri());
                if (matcher.find()) {
                    final String address = matcher.group(1);
                    final int port = Integer.parseInt(matcher.group(2));

                    final InetSocketAddress addr = SocketUtils.toSocketAddress(address, port, false);
                    connect(ctx, addr).addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (future.isSuccess()) {
                                log.debug("[HTTP][{}] Connection established: => {}:{}", method, address, port);

                                final DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
                                ctx.writeAndFlush(response).addListener(g -> {
                                    ctx.pipeline().remove(HttpServerCodec.class);
                                });
                            } else {
                                log.error("[HTTP] Error: {}/{} => {}:{}", future.cause().getMessage(), future.cause().getClass().getSimpleName(), address, port);
                                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN)).addListener(ChannelFutureListener.CLOSE);
                            }
                        }
                    }).channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            if (ctx.channel().isActive()) {
                                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                            }
                            log.info("[HTTP] Connection closed => {}:{}", address, port);
                        }
                    });
                } else {
                    log.info("[HTTP] bad CONNECT request => {}", httpRequest.uri());
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST)).addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                /*- HTTP routing */
                // GET http://www.baidu.com/ HTTP/1.1
                // Host: www.baidu.com
                // User-Agent: curl/7.64.1
                // Proxy-Connection: Keep-Alive
                final FullHttpRequest httpRequestToSend = httpRequest.retain();
                final InetSocketAddress targetAddress = getHttpRequestAddress(httpRequest);
                final int port = targetAddress.getPort();
                final String address = targetAddress.getHostString();
                forward(ctx, httpRequest).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        final HttpHeaders headers = httpRequest.headers();
                        final String connection = headers.getAsString(HttpHeaderNames.PROXY_CONNECTION);
                        if (null != connection) {
                            headers.add(HttpHeaderNames.CONNECTION, connection);
                            headers.remove(HttpHeaderNames.PROXY_CONNECTION);
                        }
                        if (future.isSuccess()) {
                            log.debug("[HTTP][{}] Connection established => {}:{}", method, address, port);
                            future.channel().writeAndFlush(httpRequestToSend);
                        } else {
                            ReferenceCountUtil.release(httpRequestToSend);

                            log.error("[HTTP] Error: {} => {}:{}", future.cause().getMessage(), address, port);

                            ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN)).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                }).channel().closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (ctx.channel().isActive()) {
                            ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        }
                        log.info("[HTTP] Connection closed => {}:{}", address, port);
                    }
                });
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private boolean authenticate(final FullHttpRequest httpRequest) {
        return null == authorization || authorization.equals(httpRequest.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION));
    }

    protected InetSocketAddress getHttpRequestAddress(final HttpRequest httpRequest) {
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

    protected ChannelFuture forward(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest) throws Exception {
        final InetSocketAddress targetAddress = getHttpRequestAddress(httpRequest);

        return factory.open(targetAddress, ctx.channel().config().getConnectTimeoutMillis(), true, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new HttpClientCodec());
//                ch.pipeline().addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext proxyCtx, final Object msg) throws Exception {
                        ctx.writeAndFlush(msg);
                    }
                });
            }
        });
    }

    /**
     * CONNECT URI pattern.
     */
    private static final Pattern CONNECT_URI_PATTERN = Pattern.compile("^([^:/?&]+):([1-9]+[0-9]*)$");

    protected ChannelFuture connect(final ChannelHandlerContext ctx, final InetSocketAddress targetAddress) throws Exception {
        ctx.channel().config().setAutoRead(false);
        return factory.open(targetAddress, ctx.channel().config().getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
                ctx.pipeline().addAfter(ctx.name(), null, new TcpInboundRedirectHandler(delegateCtx));
//                ctx.pipeline().replace(ctx.name(), null, new TcpInboundRedirectHandler(delegateCtx));

                ctx.pipeline().remove(ctx.name());
                ctx.channel().config().setAutoRead(true);
                delegateCtx.channel().config().setAutoRead(true);
            }

            @Override
            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }
        });
    }
}