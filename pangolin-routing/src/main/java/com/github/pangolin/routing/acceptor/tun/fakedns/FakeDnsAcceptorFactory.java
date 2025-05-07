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
        final String inet4Subnet = "198.18.0.1/24";
        final String inet6Subnet = "2001:2::/48";
        return new FakeDnsAcceptor(inet4Subnet, inet6Subnet, 60);
    }

}