package com.github.pangolin.routing.server;

public interface AcceptorFactory {

    Acceptor apply(final int listenPort, final String... args);

}
