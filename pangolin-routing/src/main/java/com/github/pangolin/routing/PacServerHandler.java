package com.github.pangolin.routing;

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
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Slf4j
public class PacServerHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;
    private final String pac;

    public PacServerHandler(final String pac) {
        this.pac = pac;
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
            final String hostname = getHttpRequestAddress(httpRequest).getHostString();
            String pacToUse = pac.replace("SOCKS 127.0.0.1", "SOCKS " + hostname);
            final ByteBuf body = Unpooled.copiedBuffer(pacToUse, StandardCharsets.UTF_8);
            DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK, body);
            httpResponse.headers()
                    .add("Content-Type", "application/x-ns-proxy-autoconfig")
                    .add("Content-Length", body.readableBytes());
            ctx.writeAndFlush(httpResponse);
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

}