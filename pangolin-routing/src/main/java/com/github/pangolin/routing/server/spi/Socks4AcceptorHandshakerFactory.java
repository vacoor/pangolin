package com.github.pangolin.routing.server.spi;

import com.github.pangolin.routing.handler.internal.server.Socks4ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks4MixinServerHandshaker;
import com.github.pangolin.routing.server.MixinAcceptorHandshakerFactory;

public class Socks4AcceptorHandshakerFactory implements MixinAcceptorHandshakerFactory {

    @Override
    public String name() {
        return "SOCKS4";
    }

    @Override
    public MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory) {
        return Socks4MixinServerHandshaker.of(new Socks4ProxyServerHandler(null, socketFactory));
    }

}