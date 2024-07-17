package com.github.pangolin.routing.handler.internal.client;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 *
 */
public class TrojanDatagramProxyHandler extends ChannelDuplexHandler {
    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;

    private final InetSocketAddress proxyAddress;
    private final String proxyPassword;
    private final SocketChannelFactory socketChannelFactory;
    private final Socks5AddressEncoder addressEncoder;
    private final Socks5AddressDecoder addressDecoder;

    private volatile ChannelFuture udpOverTcp;

    public TrojanDatagramProxyHandler(final InetSocketAddress proxyAddress, final String proxyPassword) {
        this(proxyAddress, proxyPassword, new StandardSocketChannelFactory());
    }

    public TrojanDatagramProxyHandler(final InetSocketAddress proxyAddress,
                                      final String proxyPassword,
                                      final SocketChannelFactory socketChannelFactory) {
        this(proxyAddress, proxyPassword, socketChannelFactory, Socks5AddressEncoder.DEFAULT, Socks5AddressDecoder.DEFAULT);
    }

    public TrojanDatagramProxyHandler(final InetSocketAddress proxyAddress,
                                      final String proxyPassword, final SocketChannelFactory socketChannelFactory,
                                      final Socks5AddressEncoder addressEncoder, final Socks5AddressDecoder addressDecoder) {
        this.proxyAddress = proxyAddress;
        this.proxyPassword = proxyPassword;
        this.socketChannelFactory = socketChannelFactory;
        this.addressEncoder = addressEncoder;
        this.addressDecoder = addressDecoder;
    }

    @Override
    public void write(final ChannelHandlerContext udpCtx, final Object msg, final ChannelPromise promise) throws Exception {
        udpOverTcp.channel().writeAndFlush(msg).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    promise.trySuccess();
                } else {
                    promise.tryFailure(future.cause());
                }
            }
        });
    }

    @Override
    public void bind(final ChannelHandlerContext udpCtx, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        udpOverTcp = socketChannelFactory.open(
                proxyAddress, udpCtx.channel().config().getConnectTimeoutMillis(),
                true, udpCtx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new TrojanProxyHandshakeHandler(proxyAddress, proxyPassword));
                        ch.pipeline().addLast(new TrojanUdpOverTcpCodec(proxyAddress, addressEncoder, addressDecoder));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                                TrojanDatagramProxyHandler.super.bind(udpCtx, localAddress, promise);
                            }

                            @Override
                            public void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket msg) throws Exception {
                                System.out.println(msg);
                                // FIXME replace recipt
//                                udpCtx.writeAndFlush(msg.retain());
                                udpCtx.fireChannelRead(msg.retain());
                            }
                        });
                    }
                });
    }


    @Slf4j
    private static class TrojanUdpOverTcpCodec extends MessageToMessageCodec<ByteBuf, DatagramPacket> {
        private final InetSocketAddress proxyAddress;
        private final Socks5AddressEncoder addressEncoder;
        private final Socks5AddressDecoder addressDecoder;

        private TrojanUdpOverTcpCodec(final InetSocketAddress proxyAddress,
                                      final Socks5AddressEncoder addressEncoder,
                                      final Socks5AddressDecoder addressDecoder) {
            this.proxyAddress = proxyAddress;
            this.addressEncoder = addressEncoder;
            this.addressDecoder = addressDecoder;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void encode(final ChannelHandlerContext ctx, final DatagramPacket packet, final List<Object> out) throws Exception {
            final InetSocketAddress recipient = packet.recipient();
            final ByteBuf rawPayload = packet.content();

            log.info("[Trojan/UDP] {} -> {} -> {}: {}", ctx.channel().localAddress(), proxyAddress, recipient, ByteBufUtil.hexDump(rawPayload));

            /*-
              If the connection is a UDP ASSOCIATE, then each UDP packet has the following format:
              +------+----------+----------+--------+---------+----------+
              | ATYP | DST.ADDR | DST.PORT | Length |  CRLF   | Payload  |
              +------+----------+----------+--------+---------+----------+
              |  1   | Variable |    2     |   2    | X'0D0A' | Variable |
              +------+----------+----------+--------+---------+----------+
             */
            final ByteBuf payloadToReplace = ctx.alloc().buffer(128 + 4 + rawPayload.readableBytes());
            writeSocketAddress(payloadToReplace, recipient, addressEncoder);
            payloadToReplace.writeShort(rawPayload.readableBytes());
            payloadToReplace.writeByte(CR).writeByte(LF);
            payloadToReplace.writeBytes(rawPayload);

            log.info("[Trojan/UDP] {} -> {}: {}", ctx.channel().localAddress(), proxyAddress, ByteBufUtil.hexDump(payloadToReplace));

            out.add(payloadToReplace);
        }

        private void writeSocketAddress(final ByteBuf buf, final InetSocketAddress address,
                                        final Socks5AddressEncoder encoder) throws Exception {
            final InetAddress addr = address.getAddress();
            if (address.isUnresolved()) {
                buf.writeByte(Socks5AddressType.DOMAIN.byteValue());
                encoder.encodeAddress(Socks5AddressType.DOMAIN, address.getHostString(), buf);
            } else if (addr instanceof Inet4Address) {
                buf.writeByte(Socks5AddressType.IPv4.byteValue());
                encoder.encodeAddress(Socks5AddressType.IPv4, addr.getHostAddress(), buf);
            } else if (addr instanceof Inet6Address) {
                buf.writeByte(Socks5AddressType.IPv6.byteValue());
                encoder.encodeAddress(Socks5AddressType.IPv6, addr.getHostAddress(), buf);
            } else {
                throw new UnknownHostException(address.toString());
            }
            buf.writeShort(address.getPort());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf rawPayload, final List<Object> out) throws Exception {
            final InetSocketAddress sender = (InetSocketAddress) ctx.channel().remoteAddress();
            final InetSocketAddress recipient = (InetSocketAddress) ctx.channel().localAddress();

            log.info("[Trojan/UDP] {} -> {}: {}", sender, recipient, ByteBufUtil.hexDump(rawPayload));

             /*-
              If the connection is a UDP ASSOCIATE, then each UDP packet has the following format:
              +------+----------+----------+--------+---------+----------+
              | ATYP | DST.ADDR | DST.PORT | Length |  CRLF   | Payload  |
              +------+----------+----------+--------+---------+----------+
              |  1   | Variable |    2     |   2    | X'0D0A' | Variable |
              +------+----------+----------+--------+---------+----------+
             */
            final Socks5AddressType dstAddrType = Socks5AddressType.valueOf(rawPayload.readByte());
            final String dstAddr = addressDecoder.decodeAddress(dstAddrType, rawPayload);
            final int dstPort = rawPayload.readUnsignedShort();

            final int length = rawPayload.readUnsignedShort();
            // SKIP CRLF(X'0D0A').
            rawPayload.readBytes(2);

            final ByteBuf payloadToUse = rawPayload.readBytes(length);

            log.info("[Trojan/UDP] {}:{} -> {} -> {}: {}", dstAddr, dstPort, sender, recipient, ByteBufUtil.hexDump(payloadToUse));

            final InetSocketAddress senderToReplace = SocketUtils.toSocketAddress(dstAddr, dstPort);
            out.add(new DatagramPacket(payloadToUse, recipient, senderToReplace));
        }
    }

}
