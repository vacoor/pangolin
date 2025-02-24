package com.github.pangolin.routing.server.fakedns;

import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.AcceptorFactory;
import com.github.pangolin.routing.server.fakedns.beta.SimpleInet4FakeDns;
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
                final String fakeSubnet = "198.18.0.1/24";

                DnsEngine fakeDns = null;
                if (null == context.attr(DnsEngine.class.getName())) {
                    fakeDns = SimpleInet4FakeDns.create(fakeSubnet, 60).asDnsEngine();
                    context.attr(DnsEngine.class.getName(), fakeDns);
                }

                final Predicate<String> domainFakePredicate = domain -> !isDirect(context, domain);
                return FakeDnsServer.startFakeDns(fakeDns, domainFakePredicate);
            }
        };
    }

    private boolean isDirect(final RouteContext context, final String domain) {
        final Route r = context.getRoute(InetSocketAddress.createUnresolved(domain, 0));
        return null == r || "DIRECT".equalsIgnoreCase(r.getUpstream());
    }
}