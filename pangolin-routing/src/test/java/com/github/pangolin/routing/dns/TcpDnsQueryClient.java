package com.github.pangolin.routing.dns;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

/**
 *
 */
@Slf4j
public class TcpDnsQueryClient {

    public static void main(String[] args) throws Exception {

//        final InetSocketAddress dnsAddress = new InetSocketAddress("114.114.114.114", 53);
        final InetSocketAddress dnsAddress = new InetSocketAddress("192.168.1.1", 53);
//        final InetSocketAddress dnsAddress = new InetSocketAddress("8.8.8.8", 53);
//        final DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsAddress, 1);
        final DefaultDnsQuery query = new DefaultDnsQuery(new SecureRandom().nextInt(), DnsOpCode.QUERY);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("baidu.com.", DnsRecordType.A));
        query.setRecursionDesired(true);
//        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("baidu.com.", DnsRecordType.AAAA));
//        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("baidu.com.", DnsRecordType.NS));


        SocketChannelFactory factory = new StandardSocketChannelFactory();
        final EventLoopGroup proxyGroup = new NioEventLoopGroup();
        factory.open(dnsAddress, 0, true, proxyGroup, new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
//                        ch.pipeline().addLast(new Socks5DatagramProxyHandler(proxyAddress));
//                        ch.pipeline().addLast(new Socks5ClientDatagramPacketCodec(proxyAddress));
//                        ch.pipeline().addLast(new SsDatagramProxyHandler(ssProxyAddress, cipher, password));
                        ch.pipeline().addLast(new TcpDnsResponseDecoder());
                        ch.pipeline().addLast(new TcpDnsQueryEncoder());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DnsResponse>() {

                            @Override
                            protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final DnsResponse datagramDnsResponse) throws Exception {
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
