package com.github.pangolin.routing.beta.tun;

import com.github.pangolin.routing.context.spi.DefaultRouteContextFactory;
import com.github.pangolin.routing.server.AcceptorFactory;

public class DefaultRouteContextFactoryFake extends DefaultRouteContextFactory {
    @Override
    protected AcceptorFactory createAcceptorFactory() {
        return new MixinAcceptorFactoryFake();
    }
}
