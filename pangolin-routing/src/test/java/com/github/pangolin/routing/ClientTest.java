package com.github.pangolin.routing;

import com.github.pangolin.routing.handler.internal.client.Socks5ProxyHandler;
import com.github.pangolin.routing.handler.internal.client.WebSocketProxyHandler;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.proxy.HttpProxyHandler;

import java.net.InetSocketAddress;
import java.net.URI;

public class ClientTest {
    public static void main(String[] args) throws InterruptedException {
        final InetSocketAddress sa = new InetSocketAddress("", 80);
//        final InetSocketAddress sa = new InetSocketAddress("wiki.baozun.com", 80);
//        final InetSocketAddress sa = new InetSocketAddress("proxy.baozun.com", 808);
        final StandardSocketChannelFactory factory = new StandardSocketChannelFactory();
        ChannelFuture open = factory.open(sa, 0, true, new NioEventLoopGroup(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addFirst(new WebSocketProxyHandler(URI.create(""), null));
                ch.pipeline().addLast(new HttpProxyHandler(new InetSocketAddress("", 808)));

                ch.pipeline().addLast(new HttpClientCodec());
                ch.pipeline().addLast(new HttpContentDecompressor());
                ch.pipeline().addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        super.channelActive(ctx);
                        final DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
//                        request.headers().set("Host", "www.baidu.com");
                        ctx.writeAndFlush(request);
//                        ctx.channel().config().setAutoRead(true);
//                        ctx.writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, "baidu.com:80"));
                    }

                    @Override
                    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
                        System.out.println("Inactive");
                        super.channelInactive(ctx);
                        ctx.close();
                    }

                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        System.out.println(msg);
                    }
                });
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
//                    future.channel().writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
                }
            }
        }).channel().closeFuture().sync();
    }
}