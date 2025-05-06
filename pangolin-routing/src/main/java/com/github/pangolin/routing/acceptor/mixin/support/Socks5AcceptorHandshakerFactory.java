package com.github.pangolin.routing.acceptor.mixin.support;

import com.github.pangolin.routing.support.handler.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;

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