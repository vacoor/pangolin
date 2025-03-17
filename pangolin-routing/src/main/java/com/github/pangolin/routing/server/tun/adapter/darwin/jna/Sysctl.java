package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

/**
 * sys/sysctl.h
 */
public interface Sysctl {
    /*-
     * Top-level identifiers
     */

    /* unused .**/
    int CTL_UNSPEC = 0;
    /* "high k*ernel": proc, limits. */
    int CTL_KERN = 1;
    /* virtual* memory. */
    int CTL_VM = 2;
    /* file sy*stem, mount type is next. */
    int CTL_VFS = 3;
    /* network*, see socket.h. */
    int CTL_NET = 4;
    /* debuggi*ng parameters. */
    int CTL_DEBUG = 5;
    /* generic* cpu/io. */
    int CTL_HW = 6;
    /* machine* dependent. */
    int CTL_MACHDEP = 7;
    /* user-le*vel. */
    int CTL_USER = 8;
    /* number *of valid top-level ids. */
    int CTL_MAXID = 9;

}
