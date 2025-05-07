package com.github.pangolin.routing.acceptor.tun.fakedns;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.acceptor.AcceptorFactory;

public class FakeDnsAcceptorFactory implements AcceptorFactory {
    private static final String TYPE = "FakeDNS";

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final String type = args[0];
        if (!TYPE.equalsIgnoreCase(type)) {
            return null;
        }
        return new FakeDnsAcceptor();
    }

}