package com.github.pangolin.proxy.server.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsQueryEncoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;

public class DnsQueryServerHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(DatagramDnsQueryDecoder.class)) {
            cp.addBefore(ctx.name(), DatagramDnsQueryDecoder.class.getName(), new DatagramDnsQueryDecoder());
        }
        if (null == cp.get(DatagramDnsResponseEncoder.class)) {
            cp.addBefore(ctx.name(), DatagramDnsResponseEncoder.class.getName(), new DatagramDnsResponseEncoder());
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramDnsQuery query) throws Exception {
        final DnsQuestion dnsQuestion = query.recordAt(DnsSection.QUESTION);
        final String name = dnsQuestion.name();

        final int ttl = 10;
        if ("iproxy.io.".equalsIgnoreCase(name)) {
            final byte[] bytes = NetUtil.createByteArrayFromIpAddressString("192.168.1.201");
            final ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            final DefaultDnsRawRecord answer = new DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, ttl, buf);

            final DatagramDnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
            response.addRecord(DnsSection.ANSWER, answer);
            ctx.writeAndFlush(response);
            return;
        }
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop()).channel(NioDatagramChannel.class).handler(new SimpleChannelInboundHandler<DatagramDnsResponse>() {

            @Override
            public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
                final ChannelPipeline cp = ctx.pipeline();
                if (null == cp.get(DatagramDnsResponseDecoder.class)) {
                    cp.addBefore(ctx.name(), DatagramDnsResponseDecoder.class.getName(), new DatagramDnsResponseDecoder());
                }
                if (null == cp.get(DatagramDnsQueryEncoder.class)) {
                    cp.addBefore(ctx.name(), DatagramDnsQueryEncoder.class.getName(), new DatagramDnsQueryEncoder());
                }
            }

            @Override
            protected void channelRead0(final ChannelHandlerContext upstream, final DatagramDnsResponse upstreamResponse) throws Exception {
                final DatagramDnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
                for (int i = 0; i < upstreamResponse.count(DnsSection.ANSWER); i++) {
                    final DnsRawRecord dnsRecord = upstreamResponse.recordAt(DnsSection.ANSWER, i);
                    if (DnsRecordType.A.equals(dnsRecord.type())) {
                        final ByteBuf buf = dnsRecord.content().retain();
                        final DefaultDnsRawRecord answer = new DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, ttl, buf);
                        response.addRecord(DnsSection.ANSWER, answer);
                    }
                }
                ctx.writeAndFlush(response);
            }
        }).bind(0).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    final DatagramDnsQuery delegateQuery = new DatagramDnsQuery(null, new InetSocketAddress("114.114.114.114", 53), query.id());
                    delegateQuery.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(name, DnsRecordType.A));
                    channelFuture.channel().writeAndFlush(delegateQuery);
                } else {
                }
            }
        }).channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                System.out.println("CLOSED");
            }
        });
    }


    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new DnsQueryServerHandler());
                    }
                }).option(ChannelOption.SO_BROADCAST, true).bind(53).sync();
    }

}