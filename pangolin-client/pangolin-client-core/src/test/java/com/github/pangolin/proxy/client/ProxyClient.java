package com.github.pangolin.proxy.client;

import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230823
 */
public class ProxyClient {
    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup ioGroup = new NioEventLoopGroup();
//        ChannelFuture cf = Channels.open("112.80.248.75", 80, new NioEventLoopGroup(), new ChannelInitializer<SocketChannel>() {
        ChannelFuture cf = Channels.open("www.sdf8aonf.com", 80, NoopAddressResolverGroup.INSTANCE, true, new NioEventLoopGroup(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
//                final URI webSocketEndpoint = URI.create("ws://127.0.0.1:1008/ws");
                final URI webSocketEndpoint = URI.create("ws://127.0.0.1:8888/ws/echo");
                final String webSocketProtocol = "";

                ch.pipeline().addLast(new HttpClientCodec());
                ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024 * 8));
                ch.pipeline().addLast(new FlowControlHandler());
                ch.pipeline().addLast(new WebSocketProxyClientHandler2(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, true, 65536, true, true
                ));
//                ch.pipeline().addFirst(new Socks5ProxyHandler(new InetSocketAddress("127.0.0.1", 1008)));
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
                        if ("XX".equals(evt)) {
                            System.out.println("XX");
                            ctx.channel().config().setAutoRead(false);
                        }
                    }

                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        ctx.executor().schedule(() -> {
                            // String get = "GET / HTTP/1.1\r\n" +
                            //         "Host: www.baidu.com\r\n" +
                            //         "User-Agent: curl/7.67.0\r\n" +
                            //         "Accept: */*\r\n" +
                            //         "\r\n";
                            // ctx.writeAndFlush(Unpooled.wrappedBuffer(get.getBytes(StandardCharsets.UTF_8)));
                            ctx.channel().config().setAutoRead(true);
                        }, 10, TimeUnit.SECONDS);
                    }

                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        try {
                            System.out.println("MSG: " + ((ByteBuf)msg).toString(StandardCharsets.UTF_8));
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }
                });
            }
        });
        Channels.shutdownGroupOnClose(cf.channel(), ioGroup);
        cf.sync().channel().closeFuture().await();
    }
}
