package com.github.pangolin.routing.node;

import com.github.pangolin.routing.node.spi.ProxyInstance;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class UrlTestHealthChecker implements HealthChecker {
    private final String url;
    private final int timeoutMillis;

    public UrlTestHealthChecker() {
        this("http://www.gstatic.com/generate_204", 3000);
    }

    public UrlTestHealthChecker(final String url, final int timeoutMillis) {
        this.url = url;
        this.timeoutMillis = timeoutMillis;
    }

    public Promise<Long> ping(final ProxyInstance server, final EventLoopGroup checkGroup) {
        return heathCheck(server, checkGroup);
    }

    public Promise<Long> heathCheck(final ProxyInstance server, final EventLoopGroup checkGroup) {
        final Promise<Long> promise = GlobalEventExecutor.INSTANCE.newPromise();
        final ChannelHandler transport = server.newProxyHandler();
        final URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort();
        port = 0 < port ? port : ("http".equals(scheme) ? 80 : "https".equals(scheme) ? 443 : port);

        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, true);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, false);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis);
        b.resolver(NoopAddressResolverGroup.INSTANCE);

        final AtomicLong sinceMs = new AtomicLong(System.currentTimeMillis());
        b.group(checkGroup).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                channel.pipeline().addLast(transport);
                channel.pipeline().addLast(new HttpClientCodec());
                channel.pipeline().addLast(new HttpObjectAggregator(1024));
                channel.pipeline().addLast(new SimpleChannelInboundHandler<HttpResponse>() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        ctx.channel().writeAndFlush(newHttpCheckRequest(uri));
                    }

                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpResponse httpResponse) throws Exception {
                        final long elapsedMs = System.currentTimeMillis() - sinceMs.get();
                        if (HttpResponseStatus.NO_CONTENT.equals(httpResponse.status())) {
                            promise.trySuccess(elapsedMs);
                        } else {
                            promise.tryFailure(new IllegalStateException(httpResponse.status().toString()));
                        }
                        ctx.close();
                    }

                    @Override
                    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                        ctx.close();
                        promise.tryFailure(cause);
                    }
                });
            }
        }).connect(host, port).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                }
            }
        });
        return promise;
    }

    private FullHttpRequest newHttpCheckRequest(final URI uri) {
        final FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
        httpRequest.headers().set(HttpHeaderNames.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        httpRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        httpRequest.headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36");
        httpRequest.headers().set(HttpHeaderNames.HOST, uri.getHost());
        httpRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return httpRequest;
    }
}