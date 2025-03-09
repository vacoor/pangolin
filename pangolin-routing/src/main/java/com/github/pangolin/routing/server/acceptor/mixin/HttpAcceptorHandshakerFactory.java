package com.github.pangolin.routing.server.acceptor.mixin;

import com.github.pangolin.routing.handler.server.HttpProxyServerHandler;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.server.acceptor.mixin.support.HttpMixinServerHandshaker;

public class HttpAcceptorHandshakerFactory implements MixinAcceptorHandshakerFactory {

    @Override
    public String name() {
        return "HTTP";
    }

    @Override
    public MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory) {
        return HttpMixinServerHandshaker.of(new HttpProxyServerHandler(null, null, socketFactory));
    }

}