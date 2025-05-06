package com.github.pangolin.routing;

import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.StandardDatagramChannelFactory;
import com.github.pangolin.routing.support.handler.client.Socks5DatagramProxyHandler;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryEncoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

/**
 *
 */
@Slf4j
public class DnsQueryTest {

    public static void main(String[] args) throws Exception {


        final InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", 2081);
        final InetSocketAddress dnsAddress = new InetSocketAddress("8.8.8.8", 53);
        final DatagramDnsQuery dnsQuery = new DatagramDnsQuery(new InetSocketAddress(0), dnsAddress, 1);
        dnsQuery.setRecursionDesired(true);
        dnsQuery.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com.", DnsRecordType.A));

        // SOCKS 5
        // final Supplier<ChannelHandler> proxyClientHandlerFactory = () -> new Socks5ClientDatagramPacketCodec(proxyAddress);
        final Supplier<ChannelHandler> proxyClientHandlerFactory = () -> new Socks5DatagramProxyHandler(proxyAddress);


//                        ch.pipeline().addLast(new SsDatagramProxyHandler(ssProxyAddress, cipher, password));

        final DatagramChannelFactory factory = new StandardDatagramChannelFactory();
        final EventLoopGroup proxyGroup = new NioEventLoopGroup();
        factory.open(proxyAddress, 0, proxyGroup, new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(DatagramChannel ch) {
                if (null != proxyClientHandlerFactory) {
                    ch.pipeline().addLast(proxyClientHandlerFactory.get());
                }

                ch.pipeline().addLast(new DatagramDnsResponseDecoder());
                ch.pipeline().addLast(new DatagramDnsQueryEncoder());
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramDnsResponse>() {

                    @Override
                    protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final DatagramDnsResponse datagramDnsResponse)
                            throws Exception {
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
        }).sync().channel().writeAndFlush(dnsQuery).channel().closeFuture().sync();
    }

}
