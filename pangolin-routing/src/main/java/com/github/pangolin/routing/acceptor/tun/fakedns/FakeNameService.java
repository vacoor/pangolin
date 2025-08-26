package com.github.pangolin.routing.acceptor.tun.fakedns;

import com.github.pangolin.routing.acceptor.tun.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.acceptor.tun.fakedns.beta.SimpleInet6FakeDns;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
public class FakeNameService implements DnsEngine {
    private final int leaseTime;
    private final SimpleInet4FakeDns inet4Dns;
    private final SimpleInet6FakeDns inet6Dns;

    public FakeNameService(final Inet4Address ipAddress4, final int cidrPrefix4,
                           final Inet6Address ipAddress6, final int cidrPrefix6,
                           final int leaseTime) {
        this.leaseTime = leaseTime;
        this.inet4Dns = new SimpleInet4FakeDns(ipAddress4, cidrPrefix4, leaseTime);
        this.inet6Dns = new SimpleInet6FakeDns(ipAddress6, cidrPrefix6, leaseTime);
    }

    @Override
    public String getHostByAddress(final byte[] address) {
        try {
            final InetAddress addr = InetAddress.getByAddress(address);
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
        if (DnsRecordType.A.equals(type)) {
            address = lookupHostAddress4(hostname);
        } else if (DnsRecordType.AAAA.equals(type)) {
            address = lookupHostAddress6(hostname);
        }

        if (null != address) {
            // log.info("Assign IP Address {} to {}, TTL={}", address.getHostAddress(), hostname, ttl);
            log.info("[DNS] {} {} {} {} {}s", dnsQuery.sender().getHostString(), hostname, type.name(), address.getHostAddress(), ttl);

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
        response.setAuthoritativeAnswer(true);
        return response;
    }

    public static FakeNameService create(final String definition4, final String definition6, final int leaseTime) {
        final int index4 = definition4.indexOf("/");
        final String address4 = 0 < index4 ? definition4.substring(0, index4) : definition4;

        final Inet4Address ipAddress4 = SimpleInet4FakeDns.checkIpAddress(address4);
        int cidrPrefix4;
        if (0 < index4 && index4 < definition4.length() - 1) {
            cidrPrefix4 = SimpleInet4FakeDns.checkPrefix(Integer.parseInt(definition4.substring(index4 + 1)));
        } else {
            cidrPrefix4 = ipAddress4.getAddress().length * Byte.SIZE;
        }

        final int index6 = definition6.indexOf("/");
        final String address6 = 0 < index6 ? definition6.substring(0, index6) : definition6;

        final Inet6Address ipAddress6 = SimpleInet6FakeDns.checkIpAddress(address6);
        int cidrPrefix6;
        if (0 < index6 && index6 < definition6.length() - 1) {
            cidrPrefix6 = SimpleInet6FakeDns.checkPrefix(Integer.parseInt(definition6.substring(index6 + 1)));
        } else {
            cidrPrefix6 = ipAddress6.getAddress().length * Byte.SIZE;
        }

        return new FakeNameService(ipAddress4, cidrPrefix4, ipAddress6, cidrPrefix6, leaseTime);
    }
}