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

    /*
     * PF_ROUTE - Routing table
     *
     * Three additional levels are defined:
     *	Fourth: address family, 0 is wildcard
     *	Fifth: type of info, defined below
     *	Sixth: flag(s) to mask with for NET_RT_FLAGS
     */
    int NET_RT_DUMP = 1;      /* dump; may limit to a.f. */
    int NET_RT_FLAGS = 2;      /* by flags, e.g. RESOLVING */
    int NET_RT_IFLIST = 3;      /* survey interface list */
    int NET_RT_STAT = 4;      /* routing statistics */
    int NET_RT_TRASH = 5;      /* routes not in table but not freed */
    int NET_RT_IFLIST2 = 6;      /* interface list with addresses */
    int NET_RT_DUMP2 = 7;      /* dump; may limit to a.f. */
    /*
     * Allows read access non-local host's MAC address
     * if the process has neighbor cache entitlement.
     */
    int NET_RT_FLAGS_PRIV = 10;
    int NET_RT_MAXID = 11;

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
