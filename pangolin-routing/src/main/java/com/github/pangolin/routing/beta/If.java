package com.github.pangolin.routing.beta;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;


/**
 * JNA mapping for <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/if.h">if.h</a>.
 */
public interface If {
    int IFNAMSIZ = 16;

    /**
     * https://github.com/torvalds/linux/blob/b31c4492884252a8360f312a0ac2049349ddf603/include/uapi/linux/in6.h#L32
     */
    class in6_addr {

    }

    /**
     * https://github.com/torvalds/linux/blob/b31c4492884252a8360f312a0ac2049349ddf603/include/uapi/linux/ipv6.h#L36
     */
    @Structure.FieldOrder({ "ifr6_addr", "ifr6_prefixlen", "ifr6_ifindex" })
    class in6_ifreq extends Structure {
        public Ifreq.sockaddr_in6 ifr6_addr;
        public int ifr6_prefixlen;
        public int ifr6_ifindex;
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

        public Ifreq(final String ifr_name, final int mtu) {
            this.ifr_name = new byte[IFNAMSIZ];
            if (ifr_name != null) {
                final byte[] bytes = ifr_name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifr_name, 0, bytes.length);
            }
            this.ifr_ifru.setType("ifru_mtu");
            this.ifr_ifru.ifru_mtu = mtu;
        }

        /*-
         * https://github.com/torvalds/linux/blob/master/include/uapi/linux/socket.h
         */
//        @FieldOrder({"ss_family", "__data"})
//        public static class sockaddr extends Structure {
//            public short ss_family;
//            public byte[] __data = new byte[14];
//        }

        public static class sockaddr extends Union {
            public sockaddr_in ipv4;
            public sockaddr_in6 ipv6;
        }

        /**
         * https://github.com/torvalds/linux/blob/master/include/uapi/linux/in.h
         */
        @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
        public static class sockaddr_in extends Structure {
            public short sin_family;
            public short sin_port;
//            public in_addr sin_addr;
            public byte[] sin_addr = new byte[4];
            public byte[] sin_zero = new byte[8];

            public sockaddr_in() {
            }

            public sockaddr_in(Pointer p) {
                super(p);
                read();
            }
        }

        /**
         * https://github.com/torvalds/linux/blob/master/include/uapi/linux/in6.h
         */
        @Structure.FieldOrder({"sin6_family", "sin6_port", "sin6_flowinfo", "sin6_addr", "sin6_scope_id"})
        public static class sockaddr_in6 extends Structure {

            public sockaddr_in6() {
            }

            public sockaddr_in6(Pointer p) {
                super(p);
                read();
            }

            public short sin6_family;
            public short sin6_port;
            public int sin6_flowinfo;
            public byte[] sin6_addr = new byte[16];
            public int sin6_scope_id;
        }


        public static class IfrIfru extends Union {
            // struct	sockaddr ifru_addr;
            // struct	sockaddr ifru_dstaddr;
            // struct	sockaddr ifru_broadaddr;
            // struct	sockaddr ifru_netmask;
            // struct  sockaddr ifru_hwaddr;
            // short	ifru_flags;
            // int	ifru_ifindex;
            // int	ifru_mtu;
            // struct  ifmap ifru_map;
            // char	ifru_slave[IFNAMSIZ];	/* Just fits the size */
            // char	ifru_newname[IFNAMSIZ];
            // void __user *	ifru_data;
            // struct	if_settings ifru_settings;

            public sockaddr ifru_addr;
            public sockaddr ifru_dstaddr;
            public sockaddr ifru_broadaddr;
            public sockaddr ifru_netmask;
            public sockaddr ifru_hwaddr;
            public short	ifru_flags;
            public int ifru_ifindex;
            public int	ifru_mtu;

            public byte[] ifru_slave = new byte[IFNAMSIZ];
            public byte[] ifru_newname = new byte[IFNAMSIZ];

        }
    }
}
