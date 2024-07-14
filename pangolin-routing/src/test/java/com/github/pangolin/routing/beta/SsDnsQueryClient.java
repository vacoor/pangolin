package com.github.pangolin.routing.beta;

import com.github.pangolin.routing.handler.codec.ss.SsAeadDatagramPacketCipherCodec;
import com.github.pangolin.routing.handler.codec.ss.SsClientDatagramPacketCodec;
import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.spi.CipherAlgorithmSpi;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

/**
 *
 */
public class SsDnsQueryClient {
    public static void main(String[] args) throws Exception {
        final InetSocketAddress proxyAddress = new InetSocketAddress("f0990972.pnd6xm1ljcfpc3b-fbnode.6pzfwf.com", 56001);
        final CipherAlgorithm cipher = CipherAlgorithmSpi.getInstance("chacha20-ietf-poly1305");

        final InetSocketAddress dnsServer = new InetSocketAddress("8.8.8.8", 53);
        DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsServer, 1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com.", DnsRecordType.A));

        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new SsAeadDatagramPacketCipherCodec((AeadCipherAlgorithm) cipher, "jASkBs", new SecureRandom()));
                        ch.pipeline().addLast(new SsClientDatagramPacketCodec(proxyAddress));

                        ch.pipeline().addLast(new DatagramDnsQueryEncoder());
                        ch.pipeline().addLast(new DatagramDnsResponseDecoder());
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
                }).option(ChannelOption.SO_BROADCAST, false).bind(0)
                .sync()
                .channel().writeAndFlush(query)
        ;

    }

}
