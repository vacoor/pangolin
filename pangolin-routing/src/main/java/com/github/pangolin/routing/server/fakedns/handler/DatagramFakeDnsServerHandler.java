package com.github.pangolin.routing.server.fakedns.handler;

import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.net.handler.DatagramDnsResponseEncoder2;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

@Slf4j
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
            cp.addBefore(ctx.name(), null, new DatagramDnsResponseEncoder2());
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramDnsQuery query) throws Exception {
        final DnsQuestion dnsQuestion = query.recordAt(DnsSection.QUESTION);
        final String domain = dnsQuestion.name();
        List<String> msInetTestDomains = Arrays.asList(
                "dns.msftncsi.com.",
                "www.msftncsi.com.",
                "www.msftconnecttest.com"
        );
        if (!msInetTestDomains.contains(domain) && checker.test(domain)) {
            DatagramDnsResponse lookup = engine.lookup(query);
            if (null != lookup) {
                ctx.writeAndFlush(lookup);
                return;
            }
        }
        if (msInetTestDomains.contains(domain)) {
            log.info("MS test: {}", domain);
        }

        ctx.fireChannelRead(query.retain());
    }

}