package com.github.pangolin.routing.acceptor.tun.fakedns;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.acceptor.AcceptorFactory;
import com.github.pangolin.routing.upstream.DirectUpstream;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;
import java.util.function.Predicate;

public class FakeDnsAcceptorFactory implements AcceptorFactory {
    private static final String TYPE = "FakeDNS";

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final String type = args[0];
        if (!TYPE.equalsIgnoreCase(type)) {
            return null;
        }
        return new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                final String fake4Subnet = "198.18.0.1/24";
                final String fake6Subnet = "2001:2::/48";
                DnsEngine fakeDns = null;
                if (null == context.attr(DnsEngine.class.getName())) {
                    fakeDns = FakeNameService.create(fake4Subnet, fake6Subnet, 60);
                    context.attr(DnsEngine.class.getName(), fakeDns);
                }

                final Predicate<String> domainFakePredicate = domain -> !isDirect(context, domain);
                return FakeDnsServer.startFakeDns(fakeDns, domainFakePredicate);
            }
        };
    }

    private boolean isDirect(final RouteContext context, final String domain) {
        final Route r = context.getRoute(InetSocketAddress.createUnresolved(domain, 0));
        return null == r || DirectUpstream.INSTANCE.name().equalsIgnoreCase(r.getUpstream());
    }
}