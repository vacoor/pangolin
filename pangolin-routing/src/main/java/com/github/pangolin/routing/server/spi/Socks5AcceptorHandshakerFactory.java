package com.github.pangolin.routing.server.spi;

import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks5MixinServerHandshaker;
import com.github.pangolin.routing.server.MixinAcceptorHandshakerFactory;

public class Socks5AcceptorHandshakerFactory implements MixinAcceptorHandshakerFactory {

    @Override
    public String name() {
        return "SOCKS5";
    }

    @Override
    public MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory) {
        return Socks5MixinServerHandshaker.of(new Socks5ProxyServerHandler(null, null, socketFactory));
    }

}