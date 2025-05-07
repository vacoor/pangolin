package com.github.pangolin.routing.acceptor.tun.fakedns;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.upstream.DirectUpstream;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;
import java.util.function.Predicate;

/**
 */
public class FakeDnsAcceptor implements Acceptor {
    private final String inet4Subnet;
    private final String inet6Subnet;
    private int leaseTime;

    public FakeDnsAcceptor(final String inet4Subnet, final String inet6Subnet, final int leaseTime) {
        this.inet4Subnet = inet4Subnet;
        this.inet6Subnet = inet6Subnet;
        this.leaseTime = leaseTime;
    }


    @Override
    public ChannelFuture start(final RouteContext context) throws Exception {
        DnsEngine fakeDns = null;
        if (null == context.attr(DnsEngine.class.getName())) {
            fakeDns = FakeNameService.create(inet4Subnet, inet6Subnet, leaseTime);
            context.attr(DnsEngine.class.getName(), fakeDns);
        }

        final Predicate<String> domainFakePredicate = domain -> !isDirect(context, domain);
        return FakeDnsServer.startFakeDns(fakeDns, domainFakePredicate);
    }

    private boolean isDirect(final RouteContext context, final String domain) {
        final Route r = context.getRoute(InetSocketAddress.createUnresolved(domain, 0));
        return null == r || DirectUpstream.INSTANCE.name().equalsIgnoreCase(r.getUpstream());
    }

}
