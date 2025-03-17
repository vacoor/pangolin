package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.github.pangolin.routing.server.tun.adapter.unix.Utils;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;


/**
 * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/if.h">net/if.h</a>
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public interface If {

    int IFNAMSIZ = 16;

    /**
     * 16 is correspondent to 4bit multicast scope field.
     * i.e. from node-local to global with some reserved/unassigned types.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/scope6_var.h">netinet6/scope6_var.h</a>
     */
    int SCOPE6_ID_MAX = 16;

    /**
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/nd6.h">netinet6/nd6.h</a>
     */
    int ND6_INFINITE_LIFETIME = 0xFFFFFFFF;

    /**
     * Socket address, internet style.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/in.h">netinet/in.h</a>
     */
    @Structure.FieldOrder({
            "sin_len", "sin_family",
            "sin_port", "sin_addr", "sin_zero"
    })
    class sockaddr_in extends Structure {
        public byte sin_len;
        public byte sin_family;
        public short sin_port;
        public byte[] sin_addr = new byte[4];
        public byte[] sin_zero = new byte[8];

        public sockaddr_in() {
        }

        public sockaddr_in(final Pointer p) {
            super(p);
            read();
        }
    }

    /**
     * Interface request structure used for socket
     * ioctl's.  All interface ioctl's must have parameter
     * definitions which begin with ifr_name.  The
     * remainder may be interface specific.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/if.h">net/if.h</a>
     */
    @Structure.FieldOrder({"ifr_name", "ifr_ifru"})
    class ifreq extends Structure {
        /**
         * if name, e.g. "en0".
         */
        public byte[] ifr_name = new byte[IFNAMSIZ];
        public ifr_ifru ifr_ifru;

        public ifreq(final String ifname) {
            Utils.writeToBytes(ifname, ifr_name);
        }

        public static class ifr_ifru extends Union {
            public sockaddr_in ifru_addr;
            public sockaddr_in ifru_dstaddr;
            public sockaddr_in ifru_broadaddr;
            public short ifru_flags;
            public int ifru_metric;
            public int ifru_mtu;
            public int ifru_phys;
            public int ifru_media;
            public int ifru_intval;
            // ...

        }
    }

    /**
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/if.h">net/if.h</a>
     */
    @Structure.FieldOrder({"ifra_name", "ifra_addr", "ifra_broadaddr", "ifra_mask"})
    class ifaliasreq extends Structure {
        /**
         * if name, e.g. "en0".
         */
        public byte[] ifra_name = new byte[IFNAMSIZ];
        public sockaddr_in ifra_addr;
        public sockaddr_in ifra_broadaddr;
        public sockaddr_in ifra_mask;

        public ifaliasreq(final String ifname) {
            Utils.writeToBytes(ifname, ifra_name);
        }
    }

    /**
     * Socket address for IPv6.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/in6.h">netinet6/in6.h</a>
     */
    @Structure.FieldOrder({"sin6_len", "sin6_family", "sin6_port", "sin6_flowinfo", "sin6_addr", "sin6_scope_id"})
    class sockaddr_in6 extends Structure {
        /**
         * length of this struct(sa_family_t).
         */
        public byte sin6_len;
        /**
         * AF_INET6 (sa_family_t).
         */
        public byte sin6_family;
        /**
         * Transport layer port # (in_port_t).
         */
        public short sin6_port;
        /**
         * IP6 flow information.
         */
        public int sin6_flowinfo;
        /**
         * IP6 address.
         */
        public byte[] sin6_addr = new byte[16];
        /**
         * scope zone index.
         */
        public int sin6_scope_id;

        public sockaddr_in6(final Pointer ptr) {
            super(ptr);
        }

    }

    /**
     * pltime/vltime are just for future reference (required to implements 2
     * hour rule for hosts).  they should never be modified by nd6_timeout or
     * anywhere else.
     * userland -> kernel: accept pltime/vltime
     * kernel -> userland: throw up everything
     * in kernel: modify preferred/expire only
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/in6_var.h">netinet6/in6_var.h</a>
     */
    @Structure.FieldOrder({"ia6t_expire", "ia6t_preferred", "ia6t_vltime", "ia6t_pltime"})
    class in6_addrlifetime extends Structure {
        /**
         * valid lifetime expiration time.
         */
        public long ia6t_expire;
        /**
         * preferred lifetime expiration time.
         */
        public long ia6t_preferred;
        /**
         * valid lifetime.
         */
        public int ia6t_vltime;
        /**
         * prefix lifetime.
         */
        public int ia6t_pltime;
    }

    /**
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/in6_var.h">netinet6/in6_var.h</a>
     */
    @Structure.FieldOrder({"ifr_name", "ifr_ifru"})
    class in6_ifreq extends Structure {
        public byte[] ifr_name = new byte[IFNAMSIZ];
        public IfrIfru ifr_ifru;

        public in6_ifreq(final String ifname) {
            Utils.writeToBytes(ifname, ifr_name);
        }

        public static class IfrIfru extends Union {
            public sockaddr_in6 ifru_addr;
            public sockaddr_in6 ifru_dstaddr;
            public int ifru_flags;
            public int ifru_flags6;
            public int ifru_metric;
            public int ifru_intval;
            public Pointer ifru_data;
            public in6_addrlifetime ifru_lifetime;
            public Pointer ifru_stat;
            public int[] ifru_scope_id = new int[SCOPE6_ID_MAX];
        }
    }


    /**
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/in6_var.h">netinet6/in6_var.h</a>
     */
    @Structure.FieldOrder({"ifra_name", "ifra_addr", "ifra_dstaddr", "ifra_prefixmask", "ifra_flags", "ifra_lifetime"})
    class in6_aliasreq extends Structure {
        public byte[] ifra_name = new byte[IFNAMSIZ];
        public sockaddr_in6 ifra_addr;
        public sockaddr_in6 ifra_dstaddr;
        public sockaddr_in6 ifra_prefixmask;
        public int ifra_flags;
        public in6_addrlifetime ifra_lifetime;

        public in6_aliasreq(final String ifname) {
            Utils.writeToBytes(ifname, ifra_name);
        }
    }


    /**
     * ifaddrs.h
     */
    @Structure.FieldOrder({
            "ifa_next", "ifa_name", "ifa_flags",
            "ifa_addr", "ifa_netmask", "ifa_dstaddr", "ifa_data"
    })
    class ifaddrs extends Structure {
        public ByRef ifa_next;
        public String ifa_name;
        public int ifa_flags;
        public Socket.sockaddr.ByRef ifa_addr;
        public Socket.sockaddr.ByRef ifa_netmask;
        public Socket.sockaddr.ByRef ifa_dstaddr;
        public Pointer ifa_data;

        public static class ByRef extends ifaddrs implements ByReference {
        }
    }

    /**
     * Structure of a Link-Level sockaddr.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/if_dl.h">net/if_dl.h</a>
     */
    @Structure.FieldOrder({
            "sdl_len", "sdl_family", "sdl_index", "sdl_type",
            "sdl_nlen", "sdl_alen", "sdl_slen", "sdl_data"
    })
    class sockaddr_dl extends Structure {
        /**
         * Total length of sockaddr.
         */
        public byte sdl_len;
        /**
         * AF_LINK.
         */
        public byte sdl_family;
        /**
         * if != 0, system given index for interface.
         */
        public short sdl_index;
        /**
         * interface type.
         */
        public byte sdl_type;
        /**
         * interface name length, no trailing 0 reqd.
         */
        public byte sdl_nlen;
        /**
         * link level address length.
         */
        public byte sdl_alen;
        /**
         * link layer selector length.
         */
        public byte sdl_slen;
        /**
         * minimum work area, can be larger; contains both if name and ll address.
         */
        public byte[] sdl_data = new byte[12];

        public sockaddr_dl(final Pointer p) {
            super(p);
        }
    }
}
