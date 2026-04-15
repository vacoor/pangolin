package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.net.channel.TunAddress;
import com.github.pangolin.routing.acceptor.tun.net.channel.TunChannel;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshakeHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshakerFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import freework.util.Bytes;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 *
 */
@Slf4j
public class TunTest2 {

    public static void main(String[] args) throws Exception {
        final String ifname = args.length > 0 ? args[0] : "TCP-0";

        // TUN EventLoop (single thread — owns the TunChannel and packet demux)
        EventLoopGroup tunGroup    = new DefaultEventLoopGroup(1);
        // Worker EventLoops — one connection is pinned to one worker (consistent hash)
        EventLoopGroup workerGroup = new DefaultEventLoopGroup(4);
        try {
            TcpConfig config = TcpConfig.builder()
                    .windowScalingEnabled(true)
                    .build();

            // childHandler: installed into each TcpSockChannel's pipeline
            ChannelHandler childHandler = new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new TcpHandshakeHandler(new TcpHandshakerFactory(config)));
                    ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                            // System.out.println(msg.toString(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(Unpooled.wrappedBuffer(Bytes.toBytes("HTTP/1.1 200 OK\r\n\r\nOK")));
                        }
                    });
                }
            };

            final Bootstrap b = new Bootstrap()
                    .group(tunGroup)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new IpPacketCodec());
                            ch.pipeline().addLast(new TcpMultiplexHandler(config, childHandler, workerGroup));
                        }
                    });

            final InterfaceAddressEx ifa = InterfaceAddressEx.of("172.16.0.1", 24);
            final Channel ch = b.bind(new TunAddress(ifname, ifa)).sync().channel();
            ch.closeFuture().sync();
        } finally {
            tunGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

}
