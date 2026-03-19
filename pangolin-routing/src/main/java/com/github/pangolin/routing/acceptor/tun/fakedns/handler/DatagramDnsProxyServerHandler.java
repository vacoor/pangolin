package com.github.pangolin.routing.acceptor.tun.fakedns.handler;

import com.github.pangolin.routing.acceptor.tun.fakedns.v2.fake.MyDnsCache;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class DatagramDnsProxyServerHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    private final DnsNameResolver resolver;
    private final MyDnsCache cache = new MyDnsCache();

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
        https://r2wind.cn/articles/20221111.html
        https://tao.zz.ac/dns/dns-svcb-https.html
        https://www.rfc-editor.org/rfc/rfc9460.html
        resolver.resolveAll(question).addListener(f -> {
            if (f.isSuccess()) {
                final String hostname = question.name();
                final DnsCnameCache dnsCnameCache = resolver.cnameCache();

                String hostnameToUse = hostname;
                while (null != hostnameToUse) {
                    final String cname = dnsCnameCache.get(hostnameWithDot(hostnameToUse));
                    if (null == cname) {
                        break;
                    }
                    // new DefaultDnsRawRecord(hostnameToUse, DnsRecordType.CNAME, ttl)
                    hostnameToUse = cname;
                }

                final List<DnsRecord> records = (List<DnsRecord>) f.getNow();
                final DnsResponse response = new DatagramDnsResponse(recipient, sender, id);
                response.addRecord(DnsSection.QUESTION, question);

                for (DnsRecord record : records) {
                    response.addRecord(DnsSection.ANSWER, record);
                }
                resolver.authoritativeDnsServerCache().get(qu)
                ctx.writeAndFlush(response);
            }
        });
        DnsResponse res = cache.getCache(question, new DatagramDnsResponse(recipient, sender, id));
        if (res.count(DnsSection.ANSWER) > 0) {
            log.info("DNS CACHE: {} -> {}", question.name(), res.recordAt(DnsSection.ANSWER));
            ctx.writeAndFlush(res);
            return;
        }
        */

        log.info("[DNS] QUERY: {}", question.name());
        resolver.query(question).addListener(f -> {
            if (f.isSuccess()) {
                final AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                try {
                    cache.cache(question, envelope.content(), ctx.channel().eventLoop());
                    DnsResponse response = getResponse(recipient, sender, id, envelope.content());
                    for (int i = 0; i < response.count(DnsSection.ANSWER); i++) {
                        DnsRecord dnsRecord = response.recordAt(DnsSection.ANSWER, i);
                        log.info("[DNS] QUERY: {} -> {}", question.name(), dnsRecord);
                    }
                    ctx.writeAndFlush(response);
                } finally {
                    envelope.release();
                }
            }
        });
    }

    private static String hostnameWithDot(String name) {
        if (StringUtil.endsWith(name, '.')) {
            return name;
        }
        return name + '.';
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
            // 使用ReferenceCountUtil.retain()自动处理引用计数，无需手动检查类型
            ReferenceCountUtil.retain(record);
            r2.addRecord(section, record);
        }
    }
}