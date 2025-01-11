package com.github.pangolin.routing.extra.fakedns.handler;

import com.github.pangolin.routing.extra.fakedns.DnsEngine;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;

import java.util.function.Predicate;

public class DatagramFakeDnsServerHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
    private final DnsEngine engine;
    private final Predicate<String> checker;

    public DatagramFakeDnsServerHandler(final DnsEngine engine, final Predicate<String> checker) {
        this.engine = engine;
        this.checker = checker;
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
        if (checker.test(domain)) {
            DatagramDnsResponse lookup = engine.lookup(query);
            if (null != lookup) {
                ctx.writeAndFlush(lookup);
                return;
            }
        }

        ctx.fireChannelRead(query.retain());
    }

}