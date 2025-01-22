package com.github.pangolin.routing.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.mixin.MixinServerHandshaker;

public interface MixinAcceptorHandshakerFactory {

    String name();

    MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory,
                                           final DatagramChannelFactory datagramFactory);

}
