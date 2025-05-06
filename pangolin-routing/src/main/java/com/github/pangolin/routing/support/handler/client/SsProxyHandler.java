package com.github.pangolin.routing.support.handler.client;

import com.github.pangolin.routing.support.handler.client.AbstractProxyHandler;
import com.github.pangolin.routing.support.handler.client.Socks5Utils;
import com.github.pangolin.routing.support.handler.codec.ss.SsSocketAeadCryptCodec;
import com.github.pangolin.routing.support.handler.codec.ss.SsSocketStreamCryptCodec;
import com.github.pangolin.routing.support.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.support.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.support.handler.codec.ss.crypto.StreamCipherAlgorithm;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

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

    /**
     * {@inheritDoc}
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), null, codec);
        super.handlerAdded(ctx);
    }

    /**
     * Write TCP connect request.
     *
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol#addressing">Addressing</a>
     */
    @Override
    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise handshakePromise) throws Exception {
        final InetSocketAddress sa = destinationAddress();
        final ByteBuf buffer = ctx.alloc().buffer();

        /*-
         * Write TCP connect request.
         *
         * Addresses used in Shadowsocks follow the SOCKS5 address format:
         * [1-byte type][variable-length host][2-byte port]
         *
         * The following address types are defined:
         * 0x01: host is a 4-byte IPv4 address.
         * 0x03: host is a variable length string, starting with a 1-byte length, followed by up to 255-byte domain name.
         * 0x04: host is a 16-byte IPv6 address.
         * The port number is a 2-byte big-endian unsigned integer.
         */
        Socks5Utils.writeAddress(buffer, sa);
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

    private ChannelHandler newCodec(final CipherAlgorithm algorithm, final String password) {
        if (algorithm instanceof StreamCipherAlgorithm) {
            return new SsSocketStreamCryptCodec((StreamCipherAlgorithm) algorithm, password, new SecureRandom());
        } else if (algorithm instanceof AeadCipherAlgorithm) {
            return new SsSocketAeadCryptCodec((AeadCipherAlgorithm) algorithm, password, new SecureRandom());
        }
        throw new UnsupportedOperationException("algorithm not supported: " + algorithm.getName());
    }

}
