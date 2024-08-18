package com.github.pangolin.routing.beta.fakedns;

import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.route.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;

import java.net.InetSocketAddress;

public class DatagramFakeDnsServerHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
    private final FakeDnsEngine4 engine;
    private final RouteContext routeContext;

    public DatagramFakeDnsServerHandler(final FakeDnsEngine4 engine, final RouteContext routeContext) {
        this.engine = engine;
        this.routeContext = routeContext;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(DatagramDnsQueryDecoder.class)) {
            cp.addBefore(ctx.name(), null, new DatagramDnsQueryDecoder());
        }
        if (null == cp.get(DatagramDnsResponseEncoder.class)) {
            cp.addBefore(ctx.name(), null, new DatagramDnsResponseEncoder());
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramDnsQuery query) throws Exception {
        final DnsQuestion dnsQuestion = query.recordAt(DnsSection.QUESTION);
        final String domain = dnsQuestion.name();
        final Route route = routeContext.getRoute(InetSocketAddress.createUnresolved(domain, 0));
        if (null == route || "DIRECT".equalsIgnoreCase(route.getUpstream())) {
            ctx.fireChannelRead(query.retain());
        } else {
            DatagramDnsResponse lookup = engine.lookup(query);
            if (null != lookup) {
                ctx.writeAndFlush(lookup);
            } else {
                ctx.fireChannelRead(query.retain());
            }
        }
    }

}