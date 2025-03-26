package com.github.pangolin.routing.handler.client;

import com.github.pangolin.routing.handler.codec.ss.*;
import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.StreamCipherAlgorithm;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.ObjectUtil;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

public class SsDatagramProxyHandler extends ChannelDuplexHandler {
    private final InetSocketAddress proxyAddress;
    private final ChannelHandler codec;

    public SsDatagramProxyHandler(final InetSocketAddress proxyAddress, final CipherAlgorithm algorithm, final String password) {
        this.proxyAddress = ObjectUtil.checkNotNull(proxyAddress, "proxyAddress");
        this.codec = newCodec(algorithm, password);
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), null, codec);
        ctx.pipeline().addBefore(ctx.name(), null, new SsClientDatagramCodec(proxyAddress));
    }

    private ChannelHandler newCodec(final CipherAlgorithm algorithm, final String password) {
        if (algorithm instanceof StreamCipherAlgorithm) {
            return new SsDatagramStreamCryptCodec((StreamCipherAlgorithm) algorithm, password, new SecureRandom());
        } else if (algorithm instanceof AeadCipherAlgorithm) {
            return new SsDatagramAeadCryptCodec((AeadCipherAlgorithm) algorithm, password, new SecureRandom());
        }
        throw new UnsupportedOperationException("algorithm not supported: " + algorithm.getName());
    }

}