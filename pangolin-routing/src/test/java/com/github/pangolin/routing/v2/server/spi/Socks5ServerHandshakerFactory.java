package com.github.pangolin.routing.v2.server.spi;

import com.github.pangolin.routing.handler.internal.server.DefaultSocks5DatagramServerFactory;
import com.github.pangolin.routing.handler.internal.server.Socks5DatagramServerFactory;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks5MixinServerHandshaker;
import com.github.pangolin.routing.v2.server.MixinServerHandshakerFactory;

public class Socks5ServerHandshakerFactory implements MixinServerHandshakerFactory {

    @Override
    public String name() {
        return "SOCKS5";
    }

    @Override
    public MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory) {
        final Socks5DatagramServerFactory datagramServerFactory = new DefaultSocks5DatagramServerFactory(datagramFactory);
        return Socks5MixinServerHandshaker.of(new Socks5ProxyServerHandler(null, null, socketFactory, datagramServerFactory));
    }

}