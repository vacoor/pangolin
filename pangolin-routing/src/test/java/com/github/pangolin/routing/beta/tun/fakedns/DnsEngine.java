package com.github.pangolin.routing.beta.tun.fakedns;

/**
 * --dns fake|rule|direct
 *
 */
public interface DnsEngine {

    byte[] resolve(final String name);

    String resolve(final byte[] address);

    boolean isFake(final byte[] address);

}
