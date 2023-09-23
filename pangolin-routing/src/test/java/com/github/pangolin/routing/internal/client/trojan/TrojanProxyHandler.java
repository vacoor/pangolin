package com.github.pangolin.routing.internal.client.trojan;

import com.github.pangolin.util.Channels;
import freework.codec.Hex;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
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
public class TrojanProxyHandler extends ChannelDuplexHandler {
    private static final byte[] CRLF = {0x0D, 0x0A};
    private final SocketAddress proxyAddress;
    private final String password;

    private volatile SocketAddress destinationAddress;

    public TrojanProxyHandler(final SocketAddress proxyAddress, final String password) {
        this.proxyAddress = ObjectUtil.checkNotNull(proxyAddress, "proxyAddress");
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), null, Channels.createClientSslContext().newHandler(ctx.alloc()));
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
     *
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        final InetSocketAddress sa = (InetSocketAddress) destinationAddress;
        final ByteBuf buffer = Unpooled.buffer();
        buffer.writeBytes(getSecretKey(password));
        buffer.writeBytes(CRLF);
        //
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

        buffer.writeBytes(CRLF);
        ctx.writeAndFlush(buffer);
        ctx.fireChannelActive();
    }

    private byte[] getSecretKey(final String password) throws NoSuchAlgorithmException {
        final byte[] hash = MessageDigest.getInstance("SHA-224").digest(null != password ? password.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        return Hex.encode(hash).getBytes(StandardCharsets.UTF_8);
    }
}
