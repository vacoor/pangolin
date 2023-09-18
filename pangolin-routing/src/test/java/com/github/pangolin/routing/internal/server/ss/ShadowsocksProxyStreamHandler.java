package com.github.pangolin.routing.internal.server.ss;

import com.github.pangolin.routing.internal.server.ss.codec.ShadowsocksStreamCipherCodec;
import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksKeyFactory;
import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksStreamCrypt;
import io.netty.channel.ChannelHandlerContext;

import java.net.SocketAddress;
import java.security.SecureRandom;

/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Protocol">Protocol</a>
 */
public class ShadowsocksProxyStreamHandler extends ShadowsocksProxyHandler {
    private final ShadowsocksStreamCrypt crypt;
    private final String password;

    public ShadowsocksProxyStreamHandler(final SocketAddress proxyAddress, final ShadowsocksStreamCrypt crypt, final String password) {
        super(proxyAddress);
        this.crypt = crypt;
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final byte[] masterKey = ShadowsocksKeyFactory.generateKey(crypt.getKeySize(), password);
        ctx.pipeline().addBefore(ctx.name(), null, new ShadowsocksStreamCipherCodec(masterKey, crypt, new SecureRandom()));
    }

}
