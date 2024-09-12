package com.github.pangolin.routing.beta.tun.macos;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;

import static java.nio.charset.StandardCharsets.US_ASCII;


/**
 * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/if.h">if.h</a>
 */
public interface If {
    int IFNAMSIZ = 16;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/in.h">in.h</a>
     */
    @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr extends Structure {
        public static class ByRef extends sockaddr implements ByReference {}

        public short sin_family;
        public short sin_port;
        public byte[] sin_addr = new byte[4];
        public byte[] sin_zero = new byte[8];

        public sockaddr() {
        }

        public sockaddr(final Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({"sa_len", "sa_family", "sa_data"})
    class sockaddr2 extends Structure {
        public static class ByRef extends sockaddr2 implements ByReference {}

        public byte sa_len;
        public byte sa_family;
        public byte[] sa_data = new byte[14];
//        public Pointer sa_data;

    }

    @Structure.FieldOrder({ "ifr_name", "ifr_ifru" })
    class Ifreq extends Structure {
        public byte[] ifr_name = new byte[IFNAMSIZ];
        public IfrIfru ifr_ifru;

        public Ifreq(final String name) {
            this.ifr_name = new byte[IFNAMSIZ];
            if (name != null) {
                final byte[] bytes = name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifr_name, 0, bytes.length);
            }
        }


        public Ifreq(final String ifr_name, final short flags) {
            this.ifr_name = new byte[IFNAMSIZ];
            if (ifr_name != null) {
                final byte[] bytes = ifr_name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifr_name, 0, bytes.length);
            }
            this.ifr_ifru.setType("ifru_flags");
            this.ifr_ifru.ifru_flags = flags;
        }

        public static class IfrIfru extends Union {
            public sockaddr ifru_addr;
            public sockaddr ifru_dstaddr;
            public sockaddr ifru_broadaddr;
            public short	ifru_flags;
            public int ifru_metric;
            public int	ifru_mtu;
            public int	ifru_phys;
            public int	ifru_media;
            public int	ifru_intval;

            // ...
        }
    }

    @Structure.FieldOrder({
            "sin_len", "sin_family",
            "sin_port", "sin_addr", "sin_zero"
    })
    class sockaddr_in extends Structure {
        public byte sin_len = 16;
        public byte sin_family;
        public short sin_port;
        public byte[] sin_addr = new byte[4];
        public byte[] sin_zero = new byte[8];

        public sockaddr_in() {
        }

        public sockaddr_in(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({"ifra_name", "ifra_addr", "ifra_broadaddr", "ifra_mask"})
    public static class in_aliasreq extends Structure {
        public byte[] ifra_name = new byte[IFNAMSIZ];
        public sockaddr_in ifra_addr;
        public sockaddr_in ifra_broadaddr;
        public sockaddr_in ifra_mask;

        public in_aliasreq(final String name) {
            this.ifra_name = new byte[IFNAMSIZ];
            if (name != null) {
                final byte[] bytes = name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifra_name, 0, bytes.length);
            }
        }
    }


    // ----------------------
    @Structure.FieldOrder({ "ifr_name", "ifr_ifru" })
    class In6Ifreq extends Structure {
        public byte[] ifr_name = new byte[IFNAMSIZ];
        public IfrIfru ifr_ifru;

        public In6Ifreq(final String name) {
            this.ifr_name = new byte[IFNAMSIZ];
            if (name != null) {
                final byte[] bytes = name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifr_name, 0, bytes.length);
            }
        }


        public In6Ifreq(final String ifr_name, final short flags) {
            this.ifr_name = new byte[IFNAMSIZ];
            if (ifr_name != null) {
                final byte[] bytes = ifr_name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifr_name, 0, bytes.length);
            }
            this.ifr_ifru.setType("ifru_flags");
            this.ifr_ifru.ifru_flags = flags;
        }

        public static class IfrIfru extends Union {
            public static final int SCOPE6_ID_MAX = 16;
            public sockaddr_in6 ifru_addr;
            public sockaddr_in6 ifru_dstaddr;
            public int ifru_flags;
            public int ifru_flags6;
            public int ifru_metric;
            public int	ifru_intval;
            public in6_addrlifetime ifru_lifetime;
            public int[] ifru_scope_id = new int[SCOPE6_ID_MAX];

            // ...
        }
    }

    /**
     *
     */
    @Structure.FieldOrder({
            "sin6_len", "sin6_family", "sin6_port",
            "sin6_flowinfo", "sin6_addr", "sin6_scope_id"
    })
    class sockaddr_in6 extends Structure {

        public sockaddr_in6() {
        }

        public sockaddr_in6(Pointer p) {
            super(p);
            read();
        }

        public byte sin6_len = 28;
        public byte sin6_family;
        public short sin6_port;
        public int sin6_flowinfo;
        public byte[] sin6_addr = new byte[16];
        public int sin6_scope_id;
    }


    /**
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/in6_var.h">in6_var.h</a>
     */
    @Structure.FieldOrder({"ifra_name", "ifra_addr", "ifra_dstaddr", "ifra_prefixmask", "ifra_flags", "ifra_lifetime"})
    public static class in6_aliasreq extends Structure {
        public byte[] ifra_name = new byte[IFNAMSIZ];
        public sockaddr_in6 ifra_addr;
        public sockaddr_in6 ifra_dstaddr;
        public sockaddr_in6 ifra_prefixmask;
        public int ifra_flags;
        public in6_addrlifetime ifra_lifetime;

        public in6_aliasreq(final String name) {
            this.ifra_name = new byte[IFNAMSIZ];
            if (name != null) {
                final byte[] bytes = name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifra_name, 0, bytes.length);
            }
        }
    }

    @Structure.FieldOrder({
            "ia6t_expire", "ia6t_preferred",
            "ia6t_vltime", "ia6t_pltime"
    })
    public static class in6_addrlifetime extends Structure {
        public static int ND6_INFINITE_LIFETIME = 0xFFFFFFFF;

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
        public int ia6t_vltime = ND6_INFINITE_LIFETIME;

        /**
         * prefix lifetime.
         */
        public int ia6t_pltime = ND6_INFINITE_LIFETIME;
    }

    @Structure.FieldOrder({"ifa_next", "ifa_name", "ifa_flags", "ifa_addr", "ifa_netmask", "ifa_dstaddr", "ifa_data"})
    class ifaddrs extends Structure {
        public static class ByRef extends ifaddrs implements ByReference {}
        public ByRef ifa_next = null;
        public String ifa_name;
        public int ifa_flags;
        public sockaddr2.ByRef ifa_addr;
        public sockaddr2.ByRef ifa_netmask;
        public sockaddr2.ByRef ifa_dstaddr;
        public Pointer ifa_data;

        public ifaddrs() {}

    }
}
