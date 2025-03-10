package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.Structure;

/**
 * Definitions related to sockets: types, address families, options.
 *
 * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/socket.h">sys/socket.h</a>
 */
public interface Socket {

    /*-
     * Types.
     */

    /**
     * stream socket.
     */
    int SOCK_STREAM = 1;

    /**
     * datagram socket.
     */
    int SOCK_DGRAM = 2;

    /**
     * raw-protocol interface.
     */
    int SOCK_RAW = 3;


    /*-
     * Address families.
     */

    /**
     * unspecified.
     */
    int AF_UNSPEC = 0;

    /**
     * IPv4.
     */
    int AF_INET = 2;

    /**
     * Internal Routing Protocol.
     */
    int AF_ROUTE = 17;

    /**
     * Link layer interface.
     */
    int AF_LINK = 18;

    /**
     * IPv6.
     */
    int AF_INET6 = 30;

    /**
     * Kernel event messages.
     */
    int AF_SYSTEM = 32;

    int AF_UTUN = 38;


    /**
     * Structure used by kernel to store most addresses.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/socket.h">sys/socket.h</a>
     */
    @Structure.FieldOrder({"sa_len", "sa_family", "sa_data"})
    class sockaddr extends Structure {
        public byte sa_len;         /* total length */
        public byte sa_family;      /* [XSI] address family */
        public byte[] sa_data = new byte[14];   /* [XSI] addr value */

        public static class ByRef extends sockaddr implements ByReference {
        }
    }
}
