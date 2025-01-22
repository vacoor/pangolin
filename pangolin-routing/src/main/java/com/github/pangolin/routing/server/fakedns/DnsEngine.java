package com.github.pangolin.routing.server.fakedns;

import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;

/**
 * --dns fake|rule|direct
 *
 */
public interface DnsEngine {

    byte[] resolve(final String name);

    String resolve(final byte[] address);

    boolean isFake(final byte[] address);

    DatagramDnsResponse lookup(DatagramDnsQuery query);
}
