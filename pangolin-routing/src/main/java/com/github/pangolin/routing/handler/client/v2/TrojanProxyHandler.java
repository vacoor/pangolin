package com.github.pangolin.routing.handler.client.v2;

import com.github.pangolin.routing.handler.client.AbstractProxyHandler;
import freework.codec.Hex;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.NetUtil;
import io.netty.util.internal.ObjectUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ConnectionPendingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @see <a href="https://trojan-gfw.github.io/trojan/protocol">The Trojan Protocol</a>
 */
@Slf4j
public class TrojanProxyHandler extends AbstractProxyHandler  {
    private static final byte[] CRLF = {0x0D, 0x0A};

    private final String password;
    private final Socks5CommandType commandType;

    public TrojanProxyHandler(final SocketAddress proxyAddress, final String password) {
        this(proxyAddress, password, Socks5CommandType.CONNECT);
    }

    public TrojanProxyHandler(final SocketAddress proxyAddress, final String password,
                              final Socks5CommandType commandType) {
        super(proxyAddress);
        this.password = password;
        this.commandType = commandType;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        ctx.pipeline().addBefore(ctx.name(), null, sslContext.newHandler(ctx.alloc()));
        super.handlerAdded(ctx);
    }

    @Override
    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise handshakePromise) throws Exception {
        final InetSocketAddress sa = destinationAddress();
        final ByteBuf buffer = ctx.alloc().buffer();

        /*-
         +-----------------------+---------+----------------+---------+----------+
         | hex(SHA224(password)) |  CRLF   | Trojan Request |  CRLF   | Payload  |
         +-----------------------+---------+----------------+---------+----------+
         |          56           | X'0D0A' |    Variable    | X'0D0A' | Variable |
         +-----------------------+---------+----------------+---------+----------+

         where Trojan Request is a SOCKS5-like request:
         +-----+------+----------+----------+
         | CMD | ATYP | DST.ADDR | DST.PORT |
         +-----+------+----------+----------+
         |  1  |  1   | Variable |    2     |
         +-----+------+----------+----------+

         where:
             o  CMD
                 o  CONNECT X'01'
                 o  UDP ASSOCIATE X'03'
             o  ATYP address type of following address
                 o  IP V4 address: X'01'
                 o  DOMAINNAME: X'03'
                 o  IP V6 address: X'04'
             o  DST.ADDR desired destination address
             o  DST.PORT desired destination port in network octet order

         If the connection is a UDP ASSOCIATE, then each UDP packet has the following format:
         +------+----------+----------+--------+---------+----------+
         | ATYP | DST.ADDR | DST.PORT | Length |  CRLF   | Payload  |
         +------+----------+----------+--------+---------+----------+
         |  1   | Variable |    2     |   2    | X'0D0A' | Variable |
         +------+----------+----------+--------+---------+----------+
         */
        buffer.writeBytes(getSecretKey(password));
        buffer.writeBytes(CRLF);

        if (Socks5CommandType.CONNECT.equals(commandType)) {
            // Write TCP connect request.
            buffer.writeByte(Socks5CommandType.CONNECT.byteValue());
            if (sa.isUnresolved()) {
                buffer.writeByte(Socks5AddressType.DOMAIN.byteValue());
                Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.DOMAIN, sa.getHostString(), buffer);
            } else {
                final String host = sa.getAddress().getHostAddress();
                if (NetUtil.isValidIpV4Address(host)) {
                    buffer.writeByte(Socks5AddressType.IPv4.byteValue());
                    Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.IPv4, host, buffer);
                } else if (NetUtil.isValidIpV6Address(host)) {
                    buffer.writeByte(Socks5AddressType.IPv6.byteValue());
                    Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.IPv6, host, buffer);
                } else {
                    throw new ConnectException("unknown address type: " + sa.getClass().getName());
                }
            }
            buffer.writeShort(sa.getPort());
        } else if (Socks5CommandType.UDP_ASSOCIATE.equals(commandType)) {
            // Write UDP associate (UDP over TCP) request.
            buffer.writeByte(Socks5AddressType.IPv4.byteValue());
            Socks5AddressEncoder.DEFAULT.encodeAddress(Socks5AddressType.IPv4, "0.0.0.0", buffer);
            buffer.writeShort(0);
        } else {
            throw new UnsupportedOperationException(String.valueOf(commandType));
        }

        buffer.writeBytes(CRLF);

        ctx.writeAndFlush(buffer, handshakePromise);
        return handshakePromise;
    }

    @Override
    protected boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        throw new UnsupportedOperationException();
    }

    private byte[] getSecretKey(final String password) throws NoSuchAlgorithmException {
        final byte[] bytes = null != password ? password.getBytes(StandardCharsets.UTF_8) : new byte[0];
        final byte[] hash = MessageDigest.getInstance("SHA-224").digest(bytes);
        return Hex.encode(hash).getBytes(StandardCharsets.UTF_8);
    }
}
