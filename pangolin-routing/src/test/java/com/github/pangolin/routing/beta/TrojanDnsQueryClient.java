package com.github.pangolin.routing.beta;

import com.github.pangolin.routing.handler.codec.ss.SsAeadDatagramPacketCipherCodec;
import com.github.pangolin.routing.handler.codec.ss.SsClientDatagramPacketCodec;
import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.spi.CipherAlgorithmSpi;
import com.github.pangolin.routing.handler.internal.client.TrojanProxyHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.dns.*;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.List;


/**
 *
 */
public class TrojanDnsQueryClient {
    private static final byte[] CRLF = {0x0D, 0x0A};

    public static class DatagramPacketEncoder extends MessageToByteEncoder<DatagramPacket> {
        @Override
        protected void encode(final ChannelHandlerContext ctx, final DatagramPacket msg, final ByteBuf out) throws Exception {
            final InetSocketAddress sa = msg.recipient();
            final ByteBuf payload = msg.content();

//            ByteBuf buffer = Unpooled.buffer(3 + payload.readableBytes() + 128);

            if (sa.isUnresolved()) {
                out.writeByte(Socks5AddressType.DOMAIN.byteValue());
                Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.DOMAIN, sa.getHostString(), out);
            } else {
                final String host = sa.getAddress().getHostAddress();
                if (NetUtil.isValidIpV4Address(host)) {
                    out.writeByte(Socks5AddressType.IPv4.byteValue());
                    Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.IPv4, host, out);
                } else if (NetUtil.isValidIpV6Address(host)) {
                    out.writeByte(Socks5AddressType.IPv6.byteValue());
                    Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.IPv6, host, out);
                } else {
                    throw new ConnectException("unknown address type: " + sa.getClass().getName());
                }
            }
            out.writeShort(sa.getPort());
            out.writeShort(payload.readableBytes());
            out.writeBytes(CRLF);
            out.writeBytes(payload);
        }

    }

    public static class DatagramPacketDecoder extends ByteToMessageDecoder {

        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
            out.add(decode(in, (InetSocketAddress) ctx.channel().localAddress()));
        }

        private DatagramPacket decode(final ByteBuf packet, final InetSocketAddress recipient) throws Exception {
            final Socks5AddressDecoder addressDecoder = Socks5AddressDecoder.DEFAULT;
            /*-
             +----+------+------+----------+----------+----------+
             |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
             +----+------+------+----------+----------+----------+
             | 2  |  1   |  1   | Variable |    2     | Variable |
             +----+------+------+----------+----------+----------+
             */
            final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(packet.readByte());
            final String dstAddr = addressDecoder.decodeAddress(dstAddrType, packet);
            final int dstPort = packet.readUnsignedShort();

            final int length = packet.readUnsignedShort();
            final ByteBuf rawPayload = packet.readBytes(length);

            /*-
             * FIXED #5760 Netty DNS Answer Section not correctly decoded
             * https://github.com/netty/netty/issues/5760
             */
            final InetSocketAddress sender = new InetSocketAddress(dstAddr, dstPort);
            return new DatagramPacket(rawPayload, recipient, sender);
        }
    }

    public static void main(String[] args) throws Exception {
        final InetSocketAddress proxyAddress = new InetSocketAddress("hk4.0de74f06-20d5-b05e-74f5-c75c1e286fc9.66dc3db5.the-best-airport.com", 443);
        final String proxyPassword = "ccccd0e2-1e27-4de4-aeea-3d88fc4887b7";

        final InetSocketAddress dnsServer = new InetSocketAddress("114.114.114.114", 53);
        DatagramDnsQuery query = new DatagramDnsQuery(new InetSocketAddress(0), dnsServer, 1);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com.", DnsRecordType.A));

        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new TrojanProxyHandler(proxyAddress, proxyPassword));

//                        ch.pipeline().addLast(new SsAeadDatagramPacketCipherCodec((AeadCipherAlgorithm) cipher, "jASkBs", new SecureRandom()));
//                        ch.pipeline().addLast(new SsClientDatagramPacketCodec(proxyAddress));

                        ch.pipeline().addLast(new DatagramPacketEncoder());
                        ch.pipeline().addLast(new DatagramDnsQueryEncoder());

                        ch.pipeline().addLast(new DatagramPacketDecoder());
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
                }).connect(dnsServer).sync()
                .channel().closeFuture().sync()
        ;

    }

}
