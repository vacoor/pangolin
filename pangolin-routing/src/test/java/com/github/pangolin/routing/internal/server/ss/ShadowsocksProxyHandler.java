package com.github.pangolin.routing.internal.server.ss;

import com.github.pangolin.routing.internal.server.ss.codec.ShadowsocksAeadCipherCodec;
import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksAeadCrypt;
import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksKeyFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import io.netty.util.internal.ObjectUtil;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ConnectionPendingException;
import java.security.SecureRandom;

/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol">Protocol</a>
 */
public class ShadowsocksProxyHandler extends ChannelDuplexHandler {
    private final SocketAddress proxyAddress;
    private final ShadowsocksAeadCrypt crypt;
    private final String password;

    private volatile SocketAddress destinationAddress;

    public ShadowsocksProxyHandler(final SocketAddress proxyAddress, final ShadowsocksAeadCrypt crypt, final String password) {
        this.proxyAddress = ObjectUtil.checkNotNull(proxyAddress, "proxyAddress");
        this.crypt = crypt;
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final byte[] masterKey = ShadowsocksKeyFactory.generateKey(crypt.getKeySize(), password);
        ctx.pipeline().addBefore(ctx.name(), null, new ShadowsocksAeadCipherCodec(masterKey, crypt, new SecureRandom()));
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, ChannelPromise promise) throws Exception {
        if (null != destinationAddress) {
            promise.setFailure(new ConnectionPendingException());
        } else {
            destinationAddress = remoteAddress;
            ctx.connect(proxyAddress, localAddress, promise);
        }
    }

    /**
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol#addressing">Addressing</a>
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        final ByteBuf buffer = Unpooled.buffer();
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
        ctx.writeAndFlush(buffer);
        ctx.fireChannelActive();
    }
}
