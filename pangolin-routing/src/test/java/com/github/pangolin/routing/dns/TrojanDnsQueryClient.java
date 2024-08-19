package com.github.pangolin.routing.dns;

import com.github.pangolin.routing.handler.internal.client.TrojanDatagramProxyHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;


/**
 *
 */
public class TrojanDnsQueryClient {

    public static void main(String[] args) throws Exception {
        final InetSocketAddress proxyAddress = new InetSocketAddress("hk4.0de74f06-20d5-b05e-74f5-c75c1e286fc9.66dc3db5.the-best-airport.com", 443);
        final String proxyPassword = "ccccd0e2-1e27-4de4-aeea-3d88fc4887b7";

        final InetSocketAddress dnsServer = new InetSocketAddress("8.8.8.8", 53);
        DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsServer, 1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com.", DnsRecordType.A));

        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new TrojanDatagramProxyHandler(proxyAddress, proxyPassword));
//                        ch.pipeline().addLast(new TrojanDatagramProxyHandler.UdpOverTcpEncoder());
//                        ch.pipeline().addLast(new TrojanDatagramProxyHandler.UdpOverTcpDecoder());

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
                }).bind(0).sync()
                .channel().closeFuture().sync()
        ;

    }

}
