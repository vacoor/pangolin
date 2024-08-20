package com.github.pangolin.routing.beta.tun;

import com.github.pangolin.routing.beta.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsDatagramChannelFactory;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsSocketChannelFactory;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.MixinAcceptorFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FakeDnsMixinAcceptorFactory extends MixinAcceptorFactory {

    @Override
    protected SocketChannelFactory createSocketChannelFactory(final RouteContext context, final String upstream) {
        final SocketChannelFactory socketChannelFactory = super.createSocketChannelFactory(context, upstream);
        final DnsEngine fakeDns = context.attr(DnsEngine.class.getName());
        return null != fakeDns ? new FakeDnsSocketChannelFactory(fakeDns, socketChannelFactory) : socketChannelFactory;
    }

    @Override
    protected DatagramChannelFactory createDatagramChannelFactory(final RouteContext context, final String upstream) {
        final DatagramChannelFactory datagramChannelFactory = super.createDatagramChannelFactory(context, upstream);
        final DnsEngine fakeDns = context.attr(DnsEngine.class.getName());
        return null != fakeDns ? new FakeDnsDatagramChannelFactory(fakeDns, datagramChannelFactory) : datagramChannelFactory;
    }

}
