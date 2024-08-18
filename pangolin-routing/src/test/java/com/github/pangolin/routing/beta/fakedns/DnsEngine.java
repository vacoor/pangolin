package com.github.pangolin.routing.beta.fakedns;

/**
 *
 */
public interface DnsEngine {

    byte[] lookup(final String name);

    String lookupX(final byte[] address);

}
