package com.github.pangolin.tun.net.linux.jna;

import com.sun.jna.NativeLong;

/**
 * JNA mapping for <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/if_tun.h">if_tun.h</a>.
 */
public interface IfTun {

    NativeLong TUNSETIFF = new NativeLong(0x400454caL);

    /* TUNSETIFF ifr flags. */

    /**
     * TUN device (No Ethernet headers).
     */
    int IFF_TUN = 0x0001;
    int IFF_TAP = 0x0002;
    int IFF_NAPI = 0x0010;
    int IFF_NAPI_FRAGS = 0x0020;

    /* Used in TUNSETIFF to bring up tun/tap without carrier. */

    int IFF_NO_CARRIER = 0x0040;

    /**
     * Do not provide packet information.
     */
    int IFF_NO_PI = 0x1000;

    /* This flag has no real effect */

    int IFF_ONE_QUEUE = 0x2000;
    int IFF_VNET_HDR = 0x4000;
    int IFF_TUN_EXCL = 0x8000;
    int IFF_MULTI_QUEUE = 0x0100;
    int IFF_ATTACH_QUEUE = 0x0200;
    int IFF_DETACH_QUEUE = 0x0400;

    /* read-only flag */

    int IFF_PERSIST = 0x0800;
    int IFF_NOFILTER = 0x1000;

}
