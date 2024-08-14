package com.github.pangolin.routing.server;

import com.github.pangolin.routing.context.spi.DefaultRouteContextFactory;

public class DefaultRouteContextFactoryFake extends DefaultRouteContextFactory {
    @Override
    protected AcceptorFactory createAcceptorFactory() {
        return new MixinAcceptorFactoryFake();
    }
}
