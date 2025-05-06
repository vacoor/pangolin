package com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna;

/**
 * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/if_utun.h">net/if_utun.h</a>
 */
public interface IfUtun {

    /**
     * Name registered by the utun kernel control.
     */
    String UTUN_CONTROL_NAME = "com.apple.net.utun_control";

    /**
     * Socket option names to manage utun.
     */
    int UTUN_OPT_IFNAME = 2;

}
