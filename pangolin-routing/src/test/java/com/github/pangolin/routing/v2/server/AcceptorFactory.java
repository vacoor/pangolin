package com.github.pangolin.routing.v2.server;

import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;

public interface AcceptorFactory {

    Acceptor apply(final int listenPort, final String... args);

}
