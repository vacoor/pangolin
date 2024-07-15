package com.github.pangolin.routing.handler.internal.client;

import com.github.pangolin.routing.handler.codec.ss.*;
import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.StreamCipherAlgorithm;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.ObjectUtil;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

public class SsDatagramProxyHandler extends ChannelDuplexHandler {
    private final InetSocketAddress proxyAddress;
    private final StreamCipherAlgorithm streamCipherAlgorithm;
    private final AeadCipherAlgorithm aeadCipherAlgorithm;
    private final String password;

    public SsDatagramProxyHandler(final InetSocketAddress proxyAddress, final CipherAlgorithm algorithm, final String password) {
        this.proxyAddress = ObjectUtil.checkNotNull(proxyAddress, "proxyAddress");
        if (algorithm instanceof StreamCipherAlgorithm) {
            aeadCipherAlgorithm = null;
            streamCipherAlgorithm = (StreamCipherAlgorithm) algorithm;
        } else if (algorithm instanceof AeadCipherAlgorithm) {
            streamCipherAlgorithm = null;
            aeadCipherAlgorithm = (AeadCipherAlgorithm) algorithm;
        } else {
            throw new UnsupportedOperationException("algorithm not supported: " + algorithm.getName());
        }
        this.password = password;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        if (null != aeadCipherAlgorithm) {
            ctx.pipeline().addBefore(ctx.name(), null, new SsAeadDatagramPacketCipherCodec(aeadCipherAlgorithm, password, new SecureRandom()));
        } else {
            ctx.pipeline().addBefore(ctx.name(), null, new SsStreamDatagramPacketCipherCodec(streamCipherAlgorithm, password, new SecureRandom()));
        }
        ctx.pipeline().addBefore(ctx.name(), null, new SsClientDatagramPacketCodec(proxyAddress));
    }
}