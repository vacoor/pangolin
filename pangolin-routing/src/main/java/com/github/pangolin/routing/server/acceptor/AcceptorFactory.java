package com.github.pangolin.routing.server.acceptor;

public interface AcceptorFactory {

    Acceptor apply(final int listenPort, final String... args);

}
