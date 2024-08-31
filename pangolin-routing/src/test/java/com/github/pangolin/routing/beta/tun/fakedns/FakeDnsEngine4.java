package com.github.pangolin.routing.beta.tun.fakedns;

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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FakeDnsEngine4 implements DnsEngine {
    private final InetAddress ipAddress;

    private final int subnetMask;
    private final int networkAddress;
    private final long ttl;
    private final FixedTtlInet4AddressAllocator allocator;

    public FakeDnsEngine4(final Inet4Address ipAddress, final int cidrPrefix, final long ttl) {
        this.ipAddress = ipAddress;
        this.subnetMask = prefixToSubnetMask(checkPrefix(cidrPrefix));
        this.networkAddress = ipAddressToInt(ipAddress) & subnetMask;
        this.ttl = ttl;
        this.allocator = createAllocator(ttl);
    }

    public FakeDnsEngine4(final Inet4Address ipAddress, final Inet4Address subnetMask, final long ttl) {
        this.ipAddress = ipAddress;
        this.subnetMask = ipAddressToInt(subnetMask);
        this.networkAddress = ipAddressToInt(ipAddress) & this.subnetMask;
        this.ttl = ttl;
        this.allocator = createAllocator(ttl);
    }

    private FixedTtlInet4AddressAllocator createAllocator(final long ttl) {
        final int size = 0xFFFFFFFF - subnetMask;
        return new FixedTtlInet4AddressAllocator(networkAddress + 3, networkAddress + size - 1, ttl);
    }

    final Map<String, FixedTtlInet4AddressAllocator.Lease> domainToLeaseMap = new ConcurrentHashMap<>();
    final Map<String, Entry> nsIpToLeaseMap = new ConcurrentHashMap<>();

    private class Entry {
        private final String domain;
        private final FixedTtlInet4AddressAllocator.Lease lease;

        private Entry(final String domain, final FixedTtlInet4AddressAllocator.Lease lease) {
            this.domain = domain;
            this.lease = lease;
        }
    }

    public byte[] resolve(final String domain) {
        int value = domainToLeaseMap.compute(domain, (k, v) -> {
            // TODO v.value == null
            if (null == v || System.currentTimeMillis() - v.timestamp >= 2 * ttl) {
                FixedTtlInet4AddressAllocator.Lease l = allocator.acquire();
                String ipToUse = NetUtil.intToIpAddress(l.value);
                System.out.println(String.format("%s -> %s", domain, ipToUse));
                nsIpToLeaseMap.put(NetUtil.intToIpAddress(l.value), new Entry(domain, l));
                return l;
            }
            return v;
        }).value;
        return 0 != value ? SocketUtils.toAddress(NetUtil.intToIpAddress(value), false).getAddress() : null;
    }

    @Override
    public String resolve(final byte[] ip) {
        String ipToUse = NetUtil.intToIpAddress(ipAddressToInt(ip));
        Entry compute = nsIpToLeaseMap.compute(ipToUse, (k, v) -> {
            if (null == v || System.currentTimeMillis() - v.lease.timestamp >= 2 * ttl) {
                return null;
            }
            return v;
        });
        return null != compute ? compute.domain : null;
    }

    @Override
    public boolean isFake(final byte[] address) {
        return (ipAddressToInt(address) & subnetMask) == networkAddress;
    }


    public DatagramDnsResponse lookup(DatagramDnsQuery query) {
        final DnsQuestion dnsQuestion = query.recordAt(DnsSection.QUESTION);
        final String domain = dnsQuestion.name();
        final long ttl = this.ttl / 1000;
        byte[] bytes = this.resolve(domain);
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

    public static FakeDnsEngine4 create(final String ip, final String subnetMask) {
        Inet4Address ipAddress = (Inet4Address) SocketUtils.toAddress(ip, false);
        Inet4Address subnetMaskAddress = (Inet4Address) SocketUtils.toAddress(subnetMask, false);

        final long ttl = TimeUnit.MINUTES.toMillis(1);
        return new FakeDnsEngine4(ipAddress, subnetMaskAddress, ttl);
    }



    private int prefixToSubnetMask(final int cidrPrefix) {
        /*-
         * Perform the shift on a long and downcast it to int afterwards.
         * This is necessary to handle a cidrPrefix of zero correctly.
         * The left shift operator on an int only uses the five least
         * significant bits of the right-hand operand. Thus -1 << 32 evaluates
         * to -1 instead of 0. The left shift operator applied on a long
         * uses the six least significant bits.
         *
         * Also see https://github.com/netty/netty/issues/2767
         */
        return (int) ((-1L << 32 - cidrPrefix) & 0xffffffff);
    }

    private static int ipAddressToInt(final Inet4Address ipAddress) {
        final byte[] ipBytes = ipAddress.getAddress();
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    static int checkPrefix(final int cidrPrefix) {
        if (0 > cidrPrefix || cidrPrefix > 32) {
            throw new IllegalArgumentException(String.format("IPv4 requires the subnet prefix to be in range of [0,32]. The prefix was: %d", cidrPrefix));
        }
        return cidrPrefix;
    }

    private static int ipAddressToInt(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }
}
