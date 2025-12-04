package com.github.pangolin.routing.acceptor.tun.fakedns.v2.fake;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;

public class MyDnsCache {
    private final DnsResponseCache v4Cache = new DnsResponseCache();
    private final DnsResponseCache v6Cache = new DnsResponseCache();
    private final DnsResponseCache httpsCache = new DnsResponseCache();

    public <T extends DnsResponse> T cache(final DnsQuestion question, final T dnsResponse, final EventLoop loop) {
        final String hostname = question.name();
        DnsRecordType type = question.type();
        if (DnsRecordType.A.equals(type)) {
            return v4Cache.cache(hostname, dnsResponse, loop);
        }
        if (DnsRecordType.AAAA.equals(type)) {
            return v6Cache.cache(hostname, dnsResponse, loop);
        }
        if (DnsRecordType.HTTPS.equals(type)) {
            return httpsCache.cache(hostname, dnsResponse, loop);
        }
        return dnsResponse;
    }

    public <T extends DnsResponse> T getCache(final DnsQuestion question, final T dnsResponse) {
        final String hostname = question.name();
        DnsRecordType type = question.type();
        if (DnsRecordType.A.equals(type)) {
            return v4Cache.getCache(hostname, dnsResponse);
        }
        if (DnsRecordType.AAAA.equals(type)) {
            return v6Cache.getCache(hostname, dnsResponse);
        }
        if (DnsRecordType.HTTPS.equals(type)) {
            return httpsCache.getCache(hostname, dnsResponse);
        }
        return dnsResponse;
    }

}
