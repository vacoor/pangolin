package com.github.pangolin.routing.beta;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryEncoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.LinkedList;
import java.util.List;

/**
 */
public class TrojanDatagramProxyHandler extends ChannelDuplexHandler {
    private final InetSocketAddress proxyAddress;
    private final String proxyPassword;

    private volatile ChannelFuture tunnel;
    private volatile ChannelHandlerContext tcpCtx;

    public TrojanDatagramProxyHandler(final InetSocketAddress proxyAddress, final String proxyPassword) {
        this.proxyAddress = proxyAddress;
        this.proxyPassword = proxyPassword;
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        tunnel.channel().writeAndFlush(ReferenceCountUtil.retain(msg)).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    promise.trySuccess();
                } else {
                    promise.tryFailure(future.cause());
                }
            }
        });
//        tcpCtx.writeAndFlush(msg, promise);
    }

    @Override
    public void bind(final ChannelHandlerContext udpCtx, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
//        super.bind(ctx, localAddress, promise);
//        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        EventLoopGroup proxyGroup = udpCtx.channel().eventLoop();
        Bootstrap b = new Bootstrap();
        tunnel = b.group(proxyGroup).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new TrojanProxyHandshakeHandler(proxyAddress, proxyPassword));
                        ch.pipeline().addLast(new TrojanDnsQueryClient.DatagramPacketEncoder());
                        ch.pipeline().addLast(new TrojanDnsQueryClient.DatagramPacketDecoder());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                                TrojanDatagramProxyHandler.super.bind(udpCtx, localAddress, promise);
                                tcpCtx = ctx;
                            }

                            @Override
                            public void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket msg) throws Exception {
                                System.out.println(msg);
                                // FIXME replace recipt
                                udpCtx.writeAndFlush(msg.retain());
                            }
                        });
                    }
                }).connect(proxyAddress);
    }

    public static void main(String[] args) throws Exception {
        final InetSocketAddress proxyAddress = new InetSocketAddress("hk4.0de74f06-20d5-b05e-74f5-c75c1e286fc9.66dc3db5.the-best-airport.com", 443);
        final String proxyPassword = "ccccd0e2-1e27-4de4-aeea-3d88fc4887b7";

        final InetSocketAddress dnsServer = new InetSocketAddress("8.8.8.8", 53);
        DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsServer, 1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com.", DnsRecordType.A));

        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new TrojanDatagramProxyHandler(proxyAddress, proxyPassword));
                        ch.pipeline().addLast(new DatagramDnsQueryEncoder());
                        ch.pipeline().addLast(new DatagramDnsResponseDecoder());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramDnsResponse>() {

                            @Override
                            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                                ctx.writeAndFlush(query);
                            }

                            @Override
                            protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final DatagramDnsResponse datagramDnsResponse) throws Exception {
                                if (datagramDnsResponse.count(DnsSection.QUESTION) > 0) {
                                    final String domain = datagramDnsResponse.recordAt(DnsSection.QUESTION).name();
                                    System.out.print(String.format("%s -> ", domain));
                                }
                                for (int i = 0, count = datagramDnsResponse.count(DnsSection.ANSWER); i < count; i++) {
                                    final DnsRawRecord dnsQuestionAnswer = datagramDnsResponse.recordAt(DnsSection.ANSWER, i);
                                    if (DnsRecordType.A.equals(dnsQuestionAnswer.type())) {
                                        System.out.print(NetUtil.bytesToIpAddress(ByteBufUtil.getBytes(dnsQuestionAnswer.content())));
                                    }
                                }
                                System.out.println();
                            }

                        });
                    }
                }).bind(0)
                .channel().closeFuture().sync()
        ;

    }
}
