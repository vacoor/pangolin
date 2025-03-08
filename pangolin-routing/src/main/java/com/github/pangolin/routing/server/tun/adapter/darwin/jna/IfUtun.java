package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

/**
 * <code>net/if_utun.h</code>
 */
public interface IfUtun {

    /**
     * Name registered by the utun kernel control
     */
    String UTUN_CONTROL_NAME = "com.apple.net.utun_control";

    /**
     * Socket option names to manage utun.
     */
    int UTUN_OPT_IFNAME = 2;

}
