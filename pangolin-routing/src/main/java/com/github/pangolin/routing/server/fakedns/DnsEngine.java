package com.github.pangolin.routing.server.fakedns;

import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;

/**
 * --dns fake|rule|direct
 *
 */
public interface DnsEngine {

    boolean isFakeAddress(final byte[] address);

    String getHostByAddress(final byte[] address);

    DatagramDnsResponse lookup(DatagramDnsQuery query);

}
