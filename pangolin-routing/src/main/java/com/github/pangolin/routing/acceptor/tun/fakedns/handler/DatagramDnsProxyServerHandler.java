package com.github.pangolin.routing.acceptor.tun.fakedns.handler;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;

import java.net.InetSocketAddress;
import java.util.List;

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
        final int id = query.id();
        final InetSocketAddress sender = query.sender();
        final InetSocketAddress recipient = query.recipient();
        final DnsQuestion question = query.recordAt(DnsSection.QUESTION);
        if (question.type().intValue() == 65) {
            final DnsResponse response = new DatagramDnsResponse(recipient, sender, id);
            response.setCode(DnsResponseCode.NOERROR);
//            response.addRecord(DnsSection.QUESTION, question);
            ctx.writeAndFlush(response);
            return;
//            question = new DefaultDnsQuestion(question.name(), DnsRecordType.A, question.dnsClass());
        }

        /*
        FIXME this code is cache but filtered.
        resolver.resolveAll(question).addListener(f -> {
            if (f.isSuccess()) {
                final List<DnsRecord> records = (List<DnsRecord>) f.getNow();
                final DnsResponse response = new DatagramDnsResponse(recipient, sender, id);
                response.addRecord(DnsSection.QUESTION, question);
                for (DnsRecord record : records) {
                    response.addRecord(DnsSection.ANSWER, record);
                }
                ctx.writeAndFlush(response);
            }
        });
        */
        resolver.query(question).addListener(f -> {
            if (f.isSuccess()) {
                final AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                try {
                    DnsResponse response = getResponse(recipient, sender, id, envelope.content());
                    ctx.writeAndFlush(response);
                } finally {
                    // envelope.release();
                }
            }
        });
    }

    private DnsResponse getResponse(InetSocketAddress sender, InetSocketAddress recipient, int id, DnsResponse serverResponse) {
        DnsResponse response = new DatagramDnsResponse(sender, recipient, id);
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