package com.github.pangolin.routing.dns;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 *
 */
@Slf4j
public class UdpDnsQueryClient {

    public static void main(String[] args) throws Exception {

//        final InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", 1082);
        final InetSocketAddress dnsAddress = new InetSocketAddress("114.114.114.114", 53);
//        final InetSocketAddress dnsAddress = new InetSocketAddress("192.168.1.1", 53);
//        final InetSocketAddress dnsAddress = new InetSocketAddress("8.8.8.8", 53);
        final DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsAddress, 1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("aliyun.com.", DnsRecordType.AAAA));
        query.setRecursionDesired(true);
//        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("baidu.com.", DnsRecordType.A));

//        DnsNameResolver resolver = new DnsNameResolverBuilder()
//                .nameServerProvider()
//                .recursionDesired(true)
//                .build();

        DatagramChannelFactory factory = new StandardDatagramChannelFactory();
        final EventLoopGroup proxyGroup = new NioEventLoopGroup();
        factory.open(dnsAddress, 0, proxyGroup, new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
//                        ch.pipeline().addLast(new Socks5DatagramProxyHandler(proxyAddress));
//                        ch.pipeline().addLast(new Socks5ClientDatagramPacketCodec(proxyAddress));
//                        ch.pipeline().addLast(new SsDatagramProxyHandler(ssProxyAddress, cipher, password));
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
                                    if (DnsRecordType.A.equals(dnsQuestionAnswer.type()) || DnsRecordType.AAAA.equals(dnsQuestionAnswer.type())) {
                                        System.out.println(NetUtil.bytesToIpAddress(ByteBufUtil.getBytes(dnsQuestionAnswer.content())));
                                    }
                                }
                                System.out.println();
                            }

                        });
                    }
                })//.option(ChannelOption.SO_BROADCAST, false).bind(0)
                .sync()
                 .channel().writeAndFlush(query)
                /*
                .channel().writeAndFlush(new DatagramPacket(
                    Unpooled.wrappedBuffer(Hex.decode("0001000000010000000000000377777705626169647503636f6d0000010001")),
                    dnsAddress
                ))
                */
                .channel().closeFuture().sync()
        ;

    }

}
