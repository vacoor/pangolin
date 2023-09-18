package com.github.pangolin.routing.internal.server.ss;

import com.github.pangolin.routing.internal.server.ss.codec.ShadowsocksAeadCipherCodec;
import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksAeadCrypt;
import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksKeyFactory;
import io.netty.channel.ChannelHandlerContext;

import java.net.SocketAddress;
import java.security.SecureRandom;

/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol">Protocol</a>
 */
public class ShadowsocksProxyAeadHandler extends ShadowsocksProxyHandler {
    private final ShadowsocksAeadCrypt crypt;
    private final String password;

    public ShadowsocksProxyAeadHandler(final SocketAddress proxyAddress, final ShadowsocksAeadCrypt crypt, final String password) {
        super(proxyAddress);
        this.crypt = crypt;
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final byte[] masterKey = ShadowsocksKeyFactory.generateKey(crypt.getKeySize(), password);
        ctx.pipeline().addBefore(ctx.name(), null, new ShadowsocksAeadCipherCodec(masterKey, crypt, new SecureRandom()));
    }

}
