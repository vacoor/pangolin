package com.github.pangolin.routing.v2.server.spi;

import com.github.pangolin.routing.handler.internal.server.HttpProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.HttpMixinServerHandshaker;
import com.github.pangolin.routing.v2.server.MixinServerHandshakerFactory;

public class HttpServerHandshakerFactory implements MixinServerHandshakerFactory {

    @Override
    public String name() {
        return "HTTP";
    }

    @Override
    public MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory) {
        return HttpMixinServerHandshaker.of(new HttpProxyServerHandler(null, null, socketFactory));
    }

}