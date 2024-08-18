package com.github.pangolin.routing.beta.fakedns;

/**
 *
 */
public interface DnsEngine {

    byte[] resolve(final String name);

    String resolve(final byte[] address);

    boolean isFake(final byte[] address);

}
