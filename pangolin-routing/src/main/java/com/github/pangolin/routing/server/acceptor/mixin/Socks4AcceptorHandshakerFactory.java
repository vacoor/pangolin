package com.github.pangolin.routing.server.acceptor.mixin;

import com.github.pangolin.routing.handler.server.Socks4ProxyServerHandler;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.server.acceptor.mixin.support.Socks4MixinServerHandshaker;

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