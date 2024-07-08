package com.github.pangolin.routing.v2.proxy;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.health.HealthChecker;
import com.netflix.loadbalancer.IPing;
import com.netflix.loadbalancer.Server;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class UrlTestChecker {
    private final String url;
    private final int timeoutMillis;
    private final EventLoopGroup group;

    public UrlTestChecker(final EventLoopGroup group) {
        this("http://www.gstatic.com/generate_204", 3000, group);
    }


    public UrlTestChecker(final String url, final int timeoutMillis, final EventLoopGroup group) {
        this.url = url;
        this.timeoutMillis = timeoutMillis;
        this.group = group;
    }

//    @Override
    public Promise<Long> checkHealth(final ProxyServer server) {
        final Promise<Long> promise = GlobalEventExecutor.INSTANCE.newPromise();
        final URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort();
        port = 0 < port ? port : ("http".equals(scheme) ? 80 : "https".equals(scheme) ? 443 : port);

        final ChannelHandler transport = server.newProxyHandler(new InetSocketAddress(host, port));

        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, true);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, false);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis);
        b.resolver(null != transport ? NoopAddressResolverGroup.INSTANCE : null);

        final AtomicLong sinceMs = new AtomicLong(System.currentTimeMillis());
        b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                if (null != transport) {
                    channel.pipeline().addLast(transport);
                }
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