package com.github.pangolin.routing.beta;

import com.github.pangolin.routing.handler.codec.socks5.Socks5DatagramPacketCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 *
 */
@Slf4j
public class Socks5DnsQueryClient {

    public static void main(String[] args) throws Exception {
        final InetSocketAddress proxyAddress = new InetSocketAddress("192.168.1.12", 1080);

//        final InetSocketAddress dnsAddress = new InetSocketAddress("192.168.1.1", 53);
        final InetSocketAddress dnsAddress = new InetSocketAddress("114.114.114.114", 53);
//        final InetSocketAddress dnsServer = new InetSocketAddress("127.0.0.1", 1080);
        DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsAddress, 1);
//        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("iproxyvacoor.io.", DnsRecordType.A));
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("www.baidu.com", DnsRecordType.A));

        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new Socks5DatagramPacketCodec(proxyAddress));
                        ch.pipeline().addLast(new DatagramDnsResponseDecoder());
                        ch.pipeline().addLast(new DatagramDnsQueryEncoder());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramDnsResponse>() {

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
                }).option(ChannelOption.SO_BROADCAST, true).bind(2080)
                .sync()
                .channel().writeAndFlush(query)
                .channel().closeFuture().sync()
        ;

    }

}
