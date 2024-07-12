package com.github.pangolin.routing.beta;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsQueryEncoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 *
 */
public class Socks5DnsQueryClient {
    private static class Socks5DatagramPacketEncoder extends MessageToMessageEncoder<DatagramPacket> {
        private final InetSocketAddress proxyAddress;

        private Socks5DatagramPacketEncoder(final InetSocketAddress proxyAddress) {
            this.proxyAddress = proxyAddress;
        }

        @Override
        protected void encode(final ChannelHandlerContext channelHandlerContext, final DatagramPacket datagramPacket, final List<Object> list) throws Exception {
            final InetSocketAddress recipient = datagramPacket.recipient();

            final Socks5AddressEncoder encoder = Socks5AddressEncoder.DEFAULT;
            final ByteBuf buf = channelHandlerContext.alloc().buffer(3 + datagramPacket.content().readableBytes() + 128);

            // skip RSV (0x0000), FRAG (0x00)
            buf.writeBytes(new byte[]{0, 0, 0});

            if (recipient.isUnresolved()) {
                buf.writeByte(Socks5AddressType.DOMAIN.byteValue());
                encoder.encodeAddress(Socks5AddressType.DOMAIN, recipient.getHostName(), buf);
            } else {
                final InetAddress address = recipient.getAddress();
                if (address instanceof Inet4Address) {
                    buf.writeByte(Socks5AddressType.IPv4.byteValue());
                    encoder.encodeAddress(Socks5AddressType.IPv4, address.getHostAddress(), buf);
                } else if (address instanceof Inet6Address) {
                    buf.writeByte(Socks5AddressType.IPv6.byteValue());
                    encoder.encodeAddress(Socks5AddressType.IPv6, address.getHostAddress(), buf);
                } else {
                    throw new IllegalStateException();
                }
            }
            buf.writeShort(recipient.getPort());
            buf.writeBytes(datagramPacket.content());

            list.add(new DatagramPacket(buf, proxyAddress, datagramPacket.sender()));
        }
    }

    public static class Socks5DatagramPacketDecoder extends MessageToMessageDecoder<DatagramPacket> {

        @Override
        protected void decode(final ChannelHandlerContext channelHandlerContext, final DatagramPacket datagramPacket, final List<Object> list) throws Exception {
            final InetSocketAddress reply = datagramPacket.sender();
            System.out.println("xxx");
            final ByteBuf payload = datagramPacket.retain().content();
            final Socks5AddressDecoder decoder = Socks5AddressDecoder.DEFAULT;
            /*-
             io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder.decode
             */
            /*-
             +----+------+------+----------+----------+----------+
             |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
             +----+------+------+----------+----------+----------+
             | 2  |  1   |  1   | Variable |    2     | Variable |
             +----+------+------+----------+----------+----------+
             */
            // skip RSV (0x0000), FRAG (0x00)
            final int rsv = payload.readUnsignedShort();
            final byte frag = payload.readByte();

            final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(payload.readByte());
            final String dstAddr = decoder.decodeAddress(dstAddrType, payload);
            final int dstPort = payload.readUnsignedShort();

            list.add(new DatagramPacket(payload, datagramPacket.recipient(), new InetSocketAddress(dstAddr, dstPort)));
        }

    }

    public static void main(String[] args) throws Exception {
        final InetSocketAddress dnsServer = new InetSocketAddress("10.88.8.8", 53);
//        final InetSocketAddress dnsServer = new InetSocketAddress("127.0.0.1", 1080);
        DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsServer, 1);
//        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("iproxyvacoor.io.", DnsRecordType.A));
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("iproxyvacoor.io.", DnsRecordType.A));
        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new Socks5DatagramPacketEncoder(new InetSocketAddress("127.0.0.1", 1080)));
                        ch.pipeline().addLast(new Socks5DatagramPacketDecoder());
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
                }).option(ChannelOption.SO_BROADCAST, true).bind(0)
                .sync()
                .channel().writeAndFlush(query)
        ;

    }

}
