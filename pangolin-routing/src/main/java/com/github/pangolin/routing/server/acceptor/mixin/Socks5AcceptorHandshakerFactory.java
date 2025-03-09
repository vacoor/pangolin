package com.github.pangolin.routing.server.acceptor.mixin;

import com.github.pangolin.routing.handler.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.server.acceptor.mixin.support.Socks5MixinServerHandshaker;

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