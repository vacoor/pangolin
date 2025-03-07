package com.github.pangolin.routing.server.fakedns.handler;

import com.github.pangolin.routing.server.fakedns.DnsEngine;
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

        /*
        if (DnsRecordType.PTR.equals(dnsQuestion.type()) && "2.0.18.198.in-addr.arpa.".equals(domain)) {
//            ByteBuf buf = Unpooled.wrappedBuffer("lan".getBytes(StandardCharsets.UTF_8));
//            final DefaultDnsRawRecord dnsQuestionAnswer = new DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.PTR, 10, buf);
            DefaultDnsPtrRecord dnsQuestionAnswer = new DefaultDnsPtrRecord(dnsQuestion.name(), dnsQuestion.dnsClass(), 10, "local.lan.com");

            final DatagramDnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
            response.addRecord(DnsSection.QUESTION, dnsQuestion);
            response.addRecord(DnsSection.ANSWER, dnsQuestionAnswer);
//            response.setAuthoritativeAnswer(true);
            ctx.writeAndFlush(response);
            return;
        }
        */

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