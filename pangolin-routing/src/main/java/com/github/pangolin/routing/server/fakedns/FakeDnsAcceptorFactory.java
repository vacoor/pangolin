package com.github.pangolin.routing.server.fakedns;

import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.AcceptorFactory;

public class FakeDnsAcceptorFactory implements AcceptorFactory {
    private static final String TYPE = "FakeDNS";

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final String type = args[0];
        if (!TYPE.equalsIgnoreCase(type)) {
            return null;
        }
        return null;
    }

}