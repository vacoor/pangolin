package com.github.pangolin.routing.acceptor.tun.fakedns.v2.fake;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsSection;

import java.util.List;

public class DnsResponseCache {
    private final DnsRecordCache answerCache = new DnsRecordCache();
    private final DnsRecordCache authoritativeDnsServerCache = new DnsRecordCache();
    private final DnsRecordCache additionalCache = new DnsRecordCache();

    public <T extends DnsResponse> T cache(final String hostname, final T dnsResponse, final EventLoop loop) {
        answerCache.clear(hostname);
        authoritativeDnsServerCache.clear(hostname);
        additionalCache.clear(hostname);

        for (int i = 0; i < dnsResponse.count(DnsSection.ANSWER); i++) {
            answerCache.cache(hostname, dnsResponse.recordAt(DnsSection.ANSWER, i), loop);
        }

        for (int i = 0; i < dnsResponse.count(DnsSection.AUTHORITY); i++) {
            authoritativeDnsServerCache.cache(hostname, dnsResponse.recordAt(DnsSection.AUTHORITY, i), loop);
        }

        for (int i = 0; i < dnsResponse.count(DnsSection.ADDITIONAL); i++) {
            additionalCache.cache(hostname, dnsResponse.recordAt(DnsSection.ADDITIONAL, i), loop);
        }

        return dnsResponse;
    }

    public <T extends DnsResponse> T getCache(final String hostname, final T dnsResponse) {
        List<DnsRecord> answers = answerCache.get(hostname);
        if (answers.isEmpty()) {
            return dnsResponse;
        }
        for (int i = 0; i < answers.size(); i++) {
            dnsResponse.addRecord(DnsSection.ANSWER, i, answers.get(i));
        }

        List<DnsRecord> authority = authoritativeDnsServerCache.get(hostname);
        for (int i = 0; i < authority.size(); i++) {
            dnsResponse.addRecord(DnsSection.AUTHORITY, i, authority.get(i));
        }

        List<DnsRecord> additional = additionalCache.get(hostname);
        for (int i = 0; i < additional.size(); i++) {
            dnsResponse.addRecord(DnsSection.ADDITIONAL, i, additional.get(i));
        }
        return dnsResponse;
    }

}
