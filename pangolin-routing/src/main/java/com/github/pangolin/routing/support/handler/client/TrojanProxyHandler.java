package com.github.pangolin.routing.support.handler.client;

import com.github.pangolin.routing.support.handler.client.AbstractProxyHandler;
import com.github.pangolin.routing.support.handler.client.Socks5Utils;
import freework.codec.Hex;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @see <a href="https://trojan-gfw.github.io/trojan/protocol">The Trojan Protocol</a>
 */
@Slf4j
public class TrojanProxyHandler extends AbstractProxyHandler {
    private static final String SECRET_KEY_DIGEST_ALGORITHM = "SHA-224";
    private static final byte[] CRLF = {0x0D, 0x0A};

    private final String password;
    private final Socks5CommandType commandType;

    public TrojanProxyHandler(final SocketAddress proxyAddress, final String password) {
        this(proxyAddress, password, Socks5CommandType.CONNECT);
    }

    public TrojanProxyHandler(final SocketAddress proxyAddress,
                              final String password, final Socks5CommandType commandType) {
        super(proxyAddress);
        this.password = password;
        this.commandType = commandType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        ctx.pipeline().addBefore(ctx.name(), null, sslContext.newHandler(ctx.alloc()));
        super.handlerAdded(ctx);
    }

    /**
     * {@inheritDoc}
     */
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
            Socks5Utils.writeAddress(buffer, sa);
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

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        throw new IllegalStateException("N-way handshake, N != 1");
    }

    private byte[] getSecretKey(final String password) throws NoSuchAlgorithmException {
        final byte[] bytes = null != password ? password.getBytes(StandardCharsets.UTF_8) : new byte[0];
        final byte[] hash = MessageDigest.getInstance(SECRET_KEY_DIGEST_ALGORITHM).digest(bytes);
        return Hex.encode(hash).getBytes(StandardCharsets.UTF_8);
    }
}
