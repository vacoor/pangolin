package com.github.pangolin.routing.acceptor.mixin.support;

import com.github.pangolin.routing.support.handler.server.Socks4ProxyServerHandler;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;

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