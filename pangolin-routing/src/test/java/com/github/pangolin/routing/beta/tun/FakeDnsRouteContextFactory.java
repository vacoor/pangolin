package com.github.pangolin.routing.beta.tun;

import com.github.pangolin.routing.context.spi.DefaultRouteContextFactory;
import com.github.pangolin.routing.server.AcceptorFactory;

public class FakeDnsRouteContextFactory extends DefaultRouteContextFactory {
    @Override
    protected AcceptorFactory createAcceptorFactory() {
        return new FakeDnsMixinAcceptorFactory();
    }
}
