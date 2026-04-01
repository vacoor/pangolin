package com.github.pangolin.routing.acceptor.tun.fakedns;

import com.github.pangolin.routing.acceptor.tun.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.acceptor.tun.fakedns.beta.SimpleInet6FakeDns;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class FakeNameService implements DnsEngine {
    private final int leaseTime;
    private final SimpleInet4FakeDns inet4Dns;
    private final SimpleInet6FakeDns inet6Dns;

    public FakeNameService(final SimpleInet4FakeDns inet4Dns, final SimpleInet6FakeDns inet6Dns, final int leaseTime) {
        this.leaseTime = leaseTime;
        this.inet4Dns = inet4Dns;
        this.inet6Dns = inet6Dns;
    }

    @Override
    public String getHostByAddress(final byte[] address) {
        try {
            final InetAddress addr = InetAddress.getByAddress(null, address);
            if (addr instanceof Inet4Address) {
                return inet4Dns.doResolve((Inet4Address) addr);
            } else if (addr instanceof Inet6Address) {
                return inet6Dns.doResolve((Inet6Address) addr);
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    @Override
    public boolean isFakeAddress(final byte[] address) {
        return (address.length == 4 && inet4Dns.isFakeAddress(address))
                || (address.length == 16 && inet6Dns.isFakeAddress(address));
    }


    private Inet4Address lookupHostAddress4(final String host) {
        return inet4Dns.doResolve(host);
    }

    private Inet6Address lookupHostAddress6(final String host) {
        return inet6Dns.doResolve(host);
    }

    @Override
    public DatagramDnsResponse lookup(final DatagramDnsQuery dnsQuery) {
        final DnsQuestion dnsQuestion = dnsQuery.recordAt(DnsSection.QUESTION);
        final String hostname = dnsQuestion.name();
        final DnsRecordType type = dnsQuestion.type();

        int ttl = leaseTime;

        InetAddress address = null;

        /*-
          https://r2wind.cn/articles/20221111.html
          https://tao.zz.ac/dns/dns-svcb-https.html
          https://www.rfc-editor.org/rfc/rfc9460.html
         */
        final int HTTPS = 65;
        if (HTTPS == type.intValue()) {
            address = lookupHostAddress4(hostname);
            if (null != address) {
                // Use pooled allocator to avoid per-query heap allocation; 18 bytes is the exact SVCB payload size.
                ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(18);
                buf.writeShort(1);  // svc priority
                buf.writeByte(0);

                buf.writeShort(1);  // KEY=ALPN
                buf.writeShort(3);  // VALUE LENGTH
                buf.writeByte(2);
                buf.writeBytes("h2".getBytes(StandardCharsets.UTF_8));

                buf.writeShort(4);   // ipv4hint
                buf.writeShort(4);
                buf.writeBytes(address.getAddress());

                DefaultDnsRawRecord httpsRecord = new DefaultDnsRawRecord(dnsQuestion.name(), DnsRecordType.valueOf(HTTPS), 10, buf);
                return newResponse(dnsQuery, httpsRecord);
            }
            return null;
        }

        if (DnsRecordType.A.equals(type)) {
            address = lookupHostAddress4(hostname);
//        } else if (DnsRecordType.AAAA.equals(type)) {
//            address = lookupHostAddress6(hostname);
        }

        if (null != address) {
            log.info("[DNS] FAKE {} {} {} {}s ({})", hostname, type.name(), address.getHostAddress(), ttl, dnsQuery.sender().getHostString());

            final DefaultDnsRawRecord dnsAnswer = new DefaultDnsRawRecord(
                    dnsQuestion.name(), type, ttl,
                    Unpooled.wrappedBuffer(address.getAddress())
            );
            return newResponse(dnsQuery, dnsAnswer);
        }

        return null;
    }

    private DatagramDnsResponse newResponse(final DatagramDnsQuery dnsQuery, final DnsRecord dnsAnswer) {
        final DnsQuestion dnsQuestion = dnsQuery.recordAt(DnsSection.QUESTION);
        final DatagramDnsResponse response = new DatagramDnsResponse(dnsQuery.recipient(), dnsQuery.sender(), dnsQuery.id());
        response.addRecord(DnsSection.QUESTION, dnsQuestion);
        response.addRecord(DnsSection.ANSWER, dnsAnswer);
        response.setRecursionAvailable(true);
        response.setAuthoritativeAnswer(true);
        return response;
    }

    public static FakeNameService create(final String definition4, final String definition6, final int leaseTime) {
        // Delegate CIDR parsing to the sub-class factories to avoid duplicating the parsing logic here.
        return new FakeNameService(
                SimpleInet4FakeDns.create(definition4, leaseTime),
                SimpleInet6FakeDns.create(definition6, leaseTime),
                leaseTime
        );
    }
}