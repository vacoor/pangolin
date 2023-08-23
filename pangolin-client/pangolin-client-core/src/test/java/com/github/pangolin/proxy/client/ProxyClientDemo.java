package com.github.pangolin.proxy.client;

import com.github.pangolin.util.Channels;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230823
 */
public class ProxyClientDemo {
    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup ioGroup = new NioEventLoopGroup();
        ChannelFuture cf = Channels.open("112.80.248.75", 80, new NioEventLoopGroup(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                 ch.pipeline().addFirst(new Socks5ProxyClientHandler(new InetSocketAddress("127.0.0.1", 1008)));
//                ch.pipeline().addFirst(new Socks5ProxyHandler(new InetSocketAddress("127.0.0.1", 1008)));
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {


                    @Override
                    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
                        ctx.executor().schedule(() -> {
                            String get = "GET / HTTP/1.1\r\n" +
                                    "Host: www.baidu.com\r\n" +
                                    "User-Agent: curl/7.67.0\r\n" +
                                    "Accept: */*\r\n" +
                                    "\r\n";
                            ctx.writeAndFlush(Unpooled.wrappedBuffer(get.getBytes(StandardCharsets.UTF_8)));
                        }, 3, TimeUnit.SECONDS);
                    }

                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        super.channelRead(ctx, msg);
                    }
                });
            }
        });
        Channels.shutdownGroupOnClose(cf.channel(), ioGroup);
        cf.sync().channel().closeFuture().await();
    }
}
