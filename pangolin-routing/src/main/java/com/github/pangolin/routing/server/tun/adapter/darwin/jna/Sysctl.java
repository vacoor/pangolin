package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

/**
 * sys/sysctl.h
 */
public interface Sysctl {
    /*
     * Top-level identifiers
     */
    int CTL_UNSPEC = 0;              /* unused */
    int CTL_KERN = 1;              /* "high kernel": proc, limits */
    int CTL_VM = 2;              /* virtual memory */
    int CTL_VFS = 3;              /* file system, mount type is next */
    int CTL_NET = 4;              /* network, see socket.h */
    int CTL_DEBUG = 5;              /* debugging parameters */
    int CTL_HW = 6;              /* generic cpu/io */
    int CTL_MACHDEP = 7;              /* machine dependent */
    int CTL_USER = 8;              /* user-level */
    int CTL_MAXID = 9;              /* number of valid top-level ids */

}
