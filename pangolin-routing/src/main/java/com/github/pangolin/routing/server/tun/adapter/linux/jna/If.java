package com.github.pangolin.routing.server.tun.adapter.linux.jna;

import com.github.pangolin.routing.server.tun.adapter.unix.Utils;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;


/**
 * JNA mapping for <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/if.h">if.h</a>.
 */
public interface If {

    int IFNAMSIZ = 16;

    /**
     * interface is up. Can be toggled through sysfs.
     */
    int IFF_UP = 1 << 0;

    /**
     * broadcast address valid.
     */
    int IFF_BROADCAST = 1 << 1;
    /**
     * turn on debugging. Can be toggled through sysfs.
     */
    int IFF_DEBUG = 1 << 2;
    /**
     * is a loopback net.
     */
    int IFF_LOOPBACK = 1 << 3;
    /**
     * interface is has p-p link.
     */
    int IFF_POINTOPOINT = 1 << 4;
    /**
     * avoid use of trailers. Can be toggled through sysfs.
     */
    int IFF_NOTRAILERS = 1 << 5;
    /**
     * interface RFC2863 OPER_UP.
     */
    int IFF_RUNNING = 1 << 6;
    /**
     * no ARP protocol. Can be toggled through sysfs.
     */
    int IFF_NOARP = 1 << 7;
    /**
     * receive all packets. Can be toggled through sysfs.
     */
    int IFF_PROMISC = 1 << 8;
    /**
     * receive all multicast packets. Can be toggled through sysfs.
     */
    int IFF_ALLMULTI = 1 << 9;
    /**
     * master of a load balancer.
     */
    int IFF_MASTER = 1 << 10;
    /**
     * slave of a load balancer.
     */
    int IFF_SLAVE = 1 << 11;
    /**
     * Supports multicast. Can be toggled through sysfs.
     */
    int IFF_MULTICAST = 1 << 12;
    /**
     * can set media type. Can be toggled through sysfs.
     */
    int IFF_PORTSEL = 1 << 13;
    /**
     * auto media select active. Can be toggled through sysfs.
     */
    int IFF_AUTOMEDIA = 1 << 14;
    /**
     * dialup device with changing addresses. Can be toggled through sysfs.
     */
    int IFF_DYNAMIC = 1 << 15;

    class sockaddr extends Union {
        public short sa_family;
        public sockaddr_in ipv4;
        public sockaddr_in6 ipv6;

        public static class ByRef extends sockaddr implements ByReference {
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/in.h">in.h</a>
     */
    @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr_in extends Structure {
        public short sin_family;
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
     * https://github.com/torvalds/linux/blob/master/include/uapi/linux/in.h
     */
    @Structure.FieldOrder({"ifr_name", "ifr_ifru"})
    class ifreq extends Structure {
        /**
         * if name, e.g. "en0".
         */
        public byte[] ifr_name = new byte[IFNAMSIZ];
        public ifreq.ifr_ifru ifr_ifru;

        public ifreq(final String ifname) {
            Utils.writeToBytes(ifname, ifr_name);
        }

        public static class ifr_ifru extends Union {
            public sockaddr_in ifru_addr;
            public sockaddr_in ifru_dstaddr;
            public sockaddr_in ifru_broadaddr;
            public sockaddr_in ifru_netmask;
            public sockaddr_in ifru_hwaddr;
            public short ifru_flags;
            public int ifru_ifindex;
            public int ifru_mtu;
            public byte[] ifru_slave = new byte[IFNAMSIZ];
            public byte[] ifru_newname = new byte[IFNAMSIZ];
            // void __user *	ifru_data;
            // struct	if_settings ifru_settings;
        }
    }


    @Structure.FieldOrder({"sin6_family", "sin6_port", "sin6_flowinfo", "sin6_addr", "sin6_scope_id"})
    class sockaddr_in6 extends Structure {
        public short sin6_family;
        public short sin6_port;
        public int sin6_flowinfo;
        public byte[] sin6_addr = new byte[16];
        public int sin6_scope_id;
    }

    /**
     * https://github.com/torvalds/linux/blob/master/include/uapi/linux/ipv6.h
     */
    @Structure.FieldOrder({"ifr6_addr", "ifr6_prefixlen", "ifr6_ifindex"})
    class in6_ifreq extends Structure {
        public byte[] ifr6_addr = new byte[16];
        public int ifr6_prefixlen;
        public int ifr6_ifindex;
    }

    @Structure.FieldOrder({"ifa_next", "ifa_name", "ifa_flags", "ifa_addr", "ifa_netmask", "ifa_ifu", "ifa_data"})
    class ifaddrs extends Structure {
        public ByRef ifa_next;
        public String ifa_name;
        public int ifa_flags;
        public sockaddr.ByRef ifa_addr;
        public sockaddr.ByRef ifa_netmask;
        public IfaIfu ifa_ifu;
        public Pointer ifa_data;

        public static class IfaIfu extends Union {
            public sockaddr ifu_broadaddr;
            public sockaddr ifu_dstaddr;
        }

        public static class ByRef extends ifaddrs implements ByReference {
        }

    }


}
