package com.github.pangolin.routing.acceptor;

public interface AcceptorFactory {

    Acceptor apply(final int listenPort, final String... args);

}
