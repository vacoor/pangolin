package com.github.pangolin.routing.support.handler.client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;

/**
 *
 */
public abstract class Socks5Utils {
    private static Socks5AddressEncoder ADDR_ENCODER = Socks5AddressEncoder.DEFAULT;

    private Socks5Utils() {
    }

    public static ByteBuf writeAddress(final ByteBuf buffer, InetSocketAddress sa) throws Exception {
        if (sa.isUnresolved()) {
            buffer.writeByte(Socks5AddressType.DOMAIN.byteValue());
            ADDR_ENCODER.encodeAddress(Socks5AddressType.DOMAIN, sa.getHostString(), buffer);
        } else {
            final String host = sa.getAddress().getHostAddress();
            if (NetUtil.isValidIpV4Address(host)) {
                buffer.writeByte(Socks5AddressType.IPv4.byteValue());
                ADDR_ENCODER.encodeAddress(Socks5AddressType.IPv4, host, buffer);
            } else if (NetUtil.isValidIpV6Address(host)) {
                buffer.writeByte(Socks5AddressType.IPv6.byteValue());
                ADDR_ENCODER.encodeAddress(Socks5AddressType.IPv6, host, buffer);
            } else {
                throw new UnsupportedOperationException("Unknown address type: " + sa.getClass().getName());
            }
        }
        buffer.writeShort(sa.getPort());
        return buffer;
    }

}
