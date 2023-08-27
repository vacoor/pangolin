package com.github.pangolin.proxy.server.http;

import com.github.pangolin.handler.SocketInboundRedirectHandler;
import com.github.pangolin.proxy.server.NettyServer;
import com.github.pangolin.util.Channels;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;

@Slf4j
public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {
    private final EventLoopGroup proxyGroup;

    public HttpProxyServerHandler(final EventLoopGroup proxyGroup) {
        this.proxyGroup = proxyGroup;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(HttpServerCodec.class)) {
            cp.addBefore(ctx.name(), null, new HttpServerCodec());
        }
        if (null == cp.get(HttpObjectAggregator.class)) {
            cp.addBefore(ctx.name(), null, new HttpObjectAggregator(8 * 1024 * 1024));
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
            if (msg instanceof HttpRequest && ((HttpRequest) msg).decoderResult().isSuccess()) {
                final HttpRequest httpRequest = (HttpRequest) msg;
                final HttpMethod method = httpRequest.method();
                final HttpHeaders headers = httpRequest.headers();
                if (HttpMethod.GET.equals(method)) {
                    // GET http://www.baidu.com/ HTTP/1.1
                    // Host: www.baidu.com
                    // User-Agent: curl/7.64.1
                    // Accept: */*
                    // Proxy-Connection: Keep-Alive
                    String asString = headers.getAsString("Proxy-Connection");
                } else if (HttpMethod.CONNECT.equals(method)) {
                    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/CONNECT
                    // CONNECT www.baidu.com:443 HTTP/1.1
                    // Host: www.baidu.com:443
                    // User-Agent: curl/7.64.1
                    // Proxy-Connection: Keep-Alive
                    // content-length: 0
                    final String proxyAuthorization = headers.getAsString("Proxy-Authorization");
                    connect(ctx, httpRequest, proxyGroup);
                    return;
                }
                ctx.writeAndFlush(new DefaultFullHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.FORBIDDEN)).addListener(ChannelFutureListener.CLOSE);
            } else {
                Channels.closeOnFlush(ctx.channel());
                log.error("Connection closed by Malformed Packet: {}", msg);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    protected void connect(final ChannelHandlerContext ctx, final HttpRequest httpRequest, final EventLoopGroup proxyGroup) throws Exception {
        final HttpHeaders headers = httpRequest.headers();
        final String host = headers.getAsString("Host");
        final String[] split = host.split(":");
        final String address = split[0];
        final int port = Integer.parseInt(split[1]);

        ctx.channel().config().setAutoRead(false);
        Channels.open(address, port, false, proxyGroup, new ChannelInboundHandlerAdapter() {
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
//                ctx.writeAndFlush(new DefaultFullHttpResponse(
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
                ch.pipeline().addLast(new HttpProxyServerHandler(new NioEventLoopGroup()));
            }
        }).sync().channel().closeFuture().sync();
    }
}