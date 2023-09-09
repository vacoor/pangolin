package com.github.pangolin.routing.internal.server.http;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Slf4j
public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;

    private final String username;
    private final String password;
    private final String authorization;

    public HttpProxyServerHandler() {
        this.username = null;
        this.password = null;
        this.authorization = null;
    }

    public HttpProxyServerHandler(final String username, final String password) {
        this.username = username;
        this.password = password;
        this.authorization = encode(username, password);
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
                log.error("Connection closed by UNKNOWN message: {}", msg.getClass());
                ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            final FullHttpRequest httpRequest = (FullHttpRequest) msg;
            final HttpMethod method = httpRequest.method();
            if (!authenticate(httpRequest)) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (HttpMethod.CONNECT.equals(method)) {
                /*- HTTPS tunnel */
                // CONNECT www.baidu.com:443 HTTP/1.1
                // Host: www.baidu.com:443
                // User-Agent: curl/7.64.1
                // Proxy-Connection: Keep-Alive
                // content-length: 0
                connect(ctx, httpRequest);
            } else {
                /*- HTTP routing */
                // GET http://www.baidu.com/ HTTP/1.1
                // Host: www.baidu.com
                // User-Agent: curl/7.64.1
                // Proxy-Connection: Keep-Alive
                forward(ctx, httpRequest);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private boolean authenticate(final FullHttpRequest httpRequest) {
        return null == authorization || authorization.equals(httpRequest.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION));
    }

    private String encode(final String username, final String password) {
        final ByteBuf buf = Unpooled.copiedBuffer(username + ':' + password, CharsetUtil.UTF_8);
        final ByteBuf encoded = Base64.encode(buf, false);
        final String encodedStr = "Basic " + encoded.toString(CharsetUtil.US_ASCII);
        buf.release();
        encoded.release();
        return encodedStr;
    }

    private InetSocketAddress getHttpRequestAddress(final HttpRequest httpRequest) {
        final HttpHeaders headers = httpRequest.headers();
        final String host = headers.get(HttpHeaderNames.HOST);
        if (null != host && !host.isEmpty()) {
            final String[] segments = host.split(":");
            final int port = segments.length > 1 ? Integer.parseInt(segments[1]) : determinePort(0, httpRequest.uri());
            return new InetSocketAddress(segments[0], port);
        } else {
            final URI uri = URI.create(httpRequest.uri());
            final int port = determinePort(uri.getPort(), httpRequest.uri());
            return new InetSocketAddress(uri.getHost(), port);
        }
    }

    private int determinePort(final int port, final String uri) {
        return port > 0 ? port : uri.toLowerCase().startsWith("https://") ? 443 : 80;
    }

    protected void forward(final ChannelHandlerContext ctx, final FullHttpRequest httpRequest) throws Exception {
        final HttpHeaders headers = httpRequest.headers();
        final String connection = headers.getAsString(HttpHeaderNames.PROXY_CONNECTION);
        if (null != connection) {
            headers.add(HttpHeaderNames.CONNECTION, connection);
            headers.remove(HttpHeaderNames.PROXY_CONNECTION);
        }

        final InetSocketAddress targetAddress = getHttpRequestAddress(httpRequest);
        final FullHttpRequest httpRequestToSend = httpRequest.retain();
        Channels.open(targetAddress, true, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new HttpClientCodec());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext proxyCtx, final Object msg) throws Exception {
                        ctx.writeAndFlush(msg);
                    }
                });
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    future.channel().writeAndFlush(httpRequestToSend);
                } else {
                    ReferenceCountUtil.release(httpRequestToSend);
                    ctx.fireExceptionCaught(future.cause());
                }
            }
        });
    }

    protected void connect(final ChannelHandlerContext ctx, final HttpRequest httpRequest) throws Exception {
        final InetSocketAddress targetAddress = getHttpRequestAddress(httpRequest);
        final String address = targetAddress.getHostString();
        final int port = targetAddress.getPort();

        ctx.channel().config().setAutoRead(false);
        Channels.open(targetAddress, false, ctx.channel().eventLoop(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
                ctx.pipeline().addAfter(ctx.name(), null, new TcpInboundRedirectHandler(delegateCtx));

                ctx.pipeline().remove(ctx.name());
                ctx.channel().config().setAutoRead(true);
                delegateCtx.channel().config().setAutoRead(true);
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    log.info("Connection established: {}", future.channel().remoteAddress());
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK)).addListener(g -> {
                        ctx.pipeline().remove(HttpServerCodec.class);
                    });
                } else {
                    log.warn("Failed to Connect to {}: {}", future.channel().remoteAddress(), future.cause().getMessage(), future.cause());
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN)).addListener(ChannelFutureListener.CLOSE);
                }
            }
        }).channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (ctx.channel().isActive()) {
                    log.info("Connection to {} closed", future.channel().remoteAddress());
                    ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }
        });
    }
}