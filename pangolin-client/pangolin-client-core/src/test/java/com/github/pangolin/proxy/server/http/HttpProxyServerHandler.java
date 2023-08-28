package com.github.pangolin.proxy.server.http;

import com.github.pangolin.handler.SocketInboundRedirectHandler;
import com.github.pangolin.proxy.server.NettyServer;
import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.CertificateException;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Slf4j
public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    private final String username;
    private final String password;
    private final String authorization;
    private final EventLoopGroup proxyGroup;

    public HttpProxyServerHandler(final EventLoopGroup proxyGroup) {
        this.username = null;
        this.password = null;
        this.authorization = null;
        this.proxyGroup = proxyGroup;
    }

    public HttpProxyServerHandler(final String username, final String password, final EventLoopGroup proxyGroup) {
        this.username = username;
        this.password = password;
        this.authorization = encode(username, password);
        this.proxyGroup = proxyGroup;
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

    private String encode(final String username, final String password) {
        final ByteBuf buf = Unpooled.copiedBuffer(username + ':' + password, CharsetUtil.UTF_8);
        final ByteBuf encoded = Base64.encode(buf, false);
        final String encodedStr = "Basic " + encoded.toString(CharsetUtil.US_ASCII);
        buf.release();
        encoded.release();
        return encodedStr;
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
            if (msg instanceof HttpRequest && ((HttpRequest) msg).decoderResult().isSuccess()) {
                final HttpRequest httpRequest = (HttpRequest) msg;
                final HttpMethod method = httpRequest.method();
                final HttpHeaders headers = httpRequest.headers();
                if (null != authorization && !authorization.equals(headers.get(HttpHeaderNames.PROXY_AUTHORIZATION))) {
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED));
                    return;
                }

                if (HttpMethod.CONNECT.equals(method)) {
                    /*-
                     * HTTPS tunnel.
                     * https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/CONNECT
                     * <pre>
                     * CONNECT www.baidu.com:443 HTTP/1.1
                     * Host: www.baidu.com:443
                     * User-Agent: curl/7.64.1
                     * Proxy-Connection: Keep-Alive
                     * content-length: 0
                     * </pre>
                     */
                    connect(ctx, httpRequest, proxyGroup);
                } else {
                    // GET http://www.baidu.com/ HTTP/1.1
                    // Host: www.baidu.com
                    // User-Agent: curl/7.64.1
                    // Proxy-Connection: Keep-Alive
                    final String connection = headers.getAsString(HttpHeaderNames.PROXY_CONNECTION);
                    if (null != connection) {
                        headers.add(HttpHeaderNames.CONNECTION, connection);
                        headers.remove(HttpHeaderNames.PROXY_CONNECTION);
                    }
                    InetSocketAddress targetAddress = getTargetAddress(httpRequest);
                    final Object msgToSend = ReferenceCountUtil.retain(msg);
                    Channels.open(targetAddress, true, proxyGroup, new ChannelInitializer<SocketChannel>() {
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
                                future.channel().writeAndFlush(msgToSend);
                            }
                        }
                    });
                }
            } else {
                Channels.closeOnFlush(ctx.channel());
                log.error("Connection closed by Malformed Packet: {}", msg);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private InetSocketAddress getTargetAddress(final HttpRequest httpRequest) {
        final HttpHeaders headers = httpRequest.headers();
        final String host = headers.getAsString("Host");
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

    protected void connect(final ChannelHandlerContext ctx, final HttpRequest httpRequest, final EventLoopGroup proxyGroup) throws Exception {
        final InetSocketAddress targetAddress = getTargetAddress(httpRequest);
        final String address = targetAddress.getHostString();
        final int port = targetAddress.getPort();

        ctx.channel().config().setAutoRead(false);
        Channels.open(targetAddress, false, proxyGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                delegateCtx.pipeline().replace(this, null, new SocketInboundRedirectHandler(ctx));
                // ctx.pipeline().replace(ctx.handler(), null, new SocketInboundRedirectHandler(delegateCtx));
                ctx.pipeline().addAfter(ctx.name(), null, new SocketInboundRedirectHandler(delegateCtx));

                ctx.pipeline().remove(ctx.name());
                delegateCtx.channel().config().setAutoRead(true);
                ctx.channel().config().setAutoRead(true);
            }
        }).addListener(future -> {
            if (future.isSuccess()) {
                log.info("Connection to {}:{}: established", address, port);
                ctx.writeAndFlush(new DefaultFullHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK)).addListener(g -> {
                    ctx.pipeline().remove(HttpServerCodec.class);
                });
            } else {
                log.warn("Failed to Connect to {}:{}: {}", address, port, future.cause());
                ctx.writeAndFlush(new DefaultFullHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.FORBIDDEN)).addListener(ChannelFutureListener.CLOSE);
            }
        }).channel().closeFuture().addListener(future -> {
            if (ctx.channel().isActive()) {
                log.info("Connection to {}:{} closed", address, port);
                Channels.closeOnFlush(ctx.channel());
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        new NettyServer(8080).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                /*
                 curl --proxy-insecure -x https://127.0.0.1:8080 "https://www.baidu.com"
                 */
//                final SslContext ssl = Channels.createServerSslContext();
//                ch.pipeline().addLast(ssl.newHandler(ch.alloc()));
                ch.pipeline().addLast(new HttpProxyServerHandler(new NioEventLoopGroup()));
            }
        }).sync().channel().closeFuture().sync();
    }
}