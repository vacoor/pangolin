package com.github.pangolin.routing.beta;

import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FakeDnsEngine4 implements DnsEngine {
    private final long ttl;
    private final LeaseAllocator4 allocator;

    public FakeDnsEngine4(final long ttl, final LeaseAllocator4 allocator) {
        this.ttl = ttl;
        this.allocator = allocator;
    }

    final Map<String, LeaseAllocator4.Lease> domainToLeaseMap = new ConcurrentHashMap<>();
    final Map<String, Binding> nsIpToLeaseMap = new ConcurrentHashMap<>();

    private class Binding {
        private final String domain;
        private final LeaseAllocator4.Lease lease;

        private Binding(final String domain, final LeaseAllocator4.Lease lease) {
            this.domain = domain;
            this.lease = lease;
        }
    }

    public byte[] lookup(final String domain) {
        // FIXME 如果不需要代理的不返回 fake-ip.
        int value = domainToLeaseMap.compute(domain, (k, v) -> {
            // TODO v.value == null
            if (null == v || System.currentTimeMillis() - v.timestamp >= 2 * ttl) {
                LeaseAllocator4.Lease l = allocator.acquire();
                String ipToUse = NetUtil.intToIpAddress(l.value);
                System.out.println(String.format("%s -> %s", domain, ipToUse));
                nsIpToLeaseMap.put(NetUtil.intToIpAddress(l.value), new Binding(domain, l));
                return l;
            }
            return v;
        }).value;
        return 0 != value ? SocketUtils.toAddress(NetUtil.intToIpAddress(value), false).getAddress() : null;
    }

    @Override
    public String nslookup(final byte[] ip) {
        String ipToUse = NetUtil.intToIpAddress(ipAddressToInt(ip));
        Binding compute = nsIpToLeaseMap.compute(ipToUse, (k, v) -> {
            if (null == v || System.currentTimeMillis() - v.lease.timestamp >= 2 * ttl) {
                return null;
            }
            return v;
        });
        return null != compute ? compute.domain : null;
    }

    private static int ipAddressToInt(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    public DatagramDnsResponse lookup(DatagramDnsQuery query) {
        final DnsQuestion dnsQuestion = query.recordAt(DnsSection.QUESTION);
        final String domain = dnsQuestion.name();
        final int ttl = 90;
        byte[] bytes = this.lookup(domain);
        if (null != bytes) {
            final ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            final DefaultDnsRawRecord dnsQuestionAnswer = new DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.A, ttl, buf);

            final DatagramDnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
            response.addRecord(DnsSection.QUESTION, dnsQuestion);
            response.addRecord(DnsSection.ANSWER, dnsQuestionAnswer);
            return response;
        }
        return null;
    }

    public static FakeDnsEngine4 create() {
        final byte[] address = SocketUtils.toAddress("198.18.0.15", false).getAddress();
        final byte[] mask = SocketUtils.toAddress("255.255.0.0", false).getAddress();

        final int maskInt = ipAddressToInt(mask);
        final int size = 0xFFFFFFFF - maskInt;
        final int subnetAddress = ipAddressToInt(address) & maskInt;
        final long ttl = TimeUnit.MINUTES.toMillis(10);

//        System.out.println(size);

//        System.out.println(NetUtil.intToIpAddress(subnetAddress));
        LeaseAllocator4 allocator = new LeaseAllocator4(subnetAddress + 3, subnetAddress + size - 1, ttl);
        return new FakeDnsEngine4(ttl, allocator);
    }

}
