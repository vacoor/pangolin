package com.github.pangolin.routing.server.acceptor.mixin;

import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;

public interface MixinAcceptorHandshakerFactory {

    String name();

    MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory,
                                           final DatagramChannelFactory datagramFactory);

}
