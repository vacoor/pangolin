package com.github.pangolin.routing.beta;

/**
 *
 */
public interface DnsEngine {

    byte[] lookup(final String name);

    String nslookup(final byte[] address);

}
