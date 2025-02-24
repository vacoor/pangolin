package com.github.pangolin.routing.server.fakedns.handler;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolver;

import java.net.InetSocketAddress;

public class DatagramDnsProxyServerHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    private final DnsNameResolver resolver;

    public DatagramDnsProxyServerHandler(final DnsNameResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramDnsQuery query) throws Exception {
        final DnsQuestion question = query.recordAt(DnsSection.QUESTION);
        resolver.query(question).addListener(f -> {
            if (f.isSuccess()) {
                final AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                ctx.writeAndFlush(getResponse(envelope.content(), query));
            }
        });
    }

    private DnsResponse getResponse(DnsResponse serverResponse, DatagramDnsQuery query) {
        DnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
        copySections(serverResponse, response);
        return response;
    }

    private void copySections(DnsResponse r1, DnsResponse r2) {
        for (DnsSection section : DnsSection.values()) {
            copySection(r1, r2, section);
        }
    }

    private void copySection(DnsResponse r1, DnsResponse r2, DnsSection section) {
        for (int i = 0; i < r1.count(section); i++) {
            DnsRecord record = r1.recordAt(section, i);
            r2.addRecord(section, record);
        }
    }
}