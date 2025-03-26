package com.github.pangolin.routing.handler.client.v2;

import com.github.pangolin.routing.handler.client.AbstractProxyHandler;
import com.github.pangolin.routing.handler.codec.ss.SsSocketAeadCryptCodec;
import com.github.pangolin.routing.handler.codec.ss.SsSocketStreamCryptCodec;
import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.StreamCipherAlgorithm;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;

/**
 * @see <a href="https://shadowsocks.org/doc/what-is-shadowsocks.html">What is Shadowsocks?</a>
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol">Protocol</a>
 */
public class SsProxyHandler extends AbstractProxyHandler {
    private final ChannelHandler codec;

    public SsProxyHandler(final SocketAddress proxyAddress, final CipherAlgorithm algorithm, final String password) {
        super(proxyAddress);
        this.codec = newCodec(algorithm, password);
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), null, codec);
        super.handlerAdded(ctx);
    }

    /**
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol#addressing">Addressing</a>
     */
    @Override
    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise handshakePromise) throws Exception {
        final InetSocketAddress sa = destinationAddress();
        final ByteBuf buffer = ctx.alloc().buffer();
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

        ctx.writeAndFlush(buffer, handshakePromise);
        return handshakePromise;
    }

    @Override
    protected boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        throw new UnsupportedOperationException();
    }

    private ChannelHandler newCodec(final CipherAlgorithm algorithm, final String password) {
        if (algorithm instanceof StreamCipherAlgorithm) {
            return new SsSocketStreamCryptCodec((StreamCipherAlgorithm) algorithm, password, new SecureRandom());
        } else if (algorithm instanceof AeadCipherAlgorithm) {
            return new SsSocketAeadCryptCodec((AeadCipherAlgorithm) algorithm, password, new SecureRandom());
        }
        throw new UnsupportedOperationException("algorithm not supported: " + algorithm.getName());
    }

}
