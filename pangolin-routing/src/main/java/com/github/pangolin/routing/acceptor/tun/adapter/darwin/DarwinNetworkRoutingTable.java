package com.github.pangolin.routing.acceptor.tun.adapter.darwin;

import com.github.pangolin.routing.acceptor.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.DarwinUtils.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.If.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Sysctl.CTL_NET;
import static com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2.cidrToNetmaskAddress;
import static com.sun.jna.Pointer.NULL;

/**
 * Manipulate the routing tables on mac OS.
 */
public class DarwinNetworkRoutingTable extends NetworkRoutingTable {

    /**
     * Lib C instance.
     */
    private static final LibC LIBC = LibC.INSTANTCE;

    /**
     * Placeholder pointer to help avoid auto-allocation of memory where a
     * Structure needs a valid pointer but want to avoid actually reading from it.
     */
    private static final Pointer PLACEHOLDER_MEMORY = new Pointer(0) {
        @Override
        public Pointer share(final long offset, final long sz) {
            return this;
        }
    };

    private static final int RT_MSGHDR_SIZE = new com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.rt_msghdr(PLACEHOLDER_MEMORY).size();
    private static final int SOCKADDR_DL_SIZE = new sockaddr_dl(PLACEHOLDER_MEMORY).size();
    private static final int SOCKADDR_IN6_SIZE = new sockaddr_in6(PLACEHOLDER_MEMORY).size();

    /**
     * Default instance.
     */
    private static final DarwinNetworkRoutingTable INSTANCE = new DarwinNetworkRoutingTable();

    /**
     * Private constructor.
     */
    private DarwinNetworkRoutingTable() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final InetAddress dst, final byte prefix, final InetAddress gw, final String ifname, final int metric) {
        add0(dst, prefix, gw, ifname);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final InetAddress dst, final byte prefix, final InetAddress gw, final int ifindex, final int metric) {
        add0(dst, prefix, gw, (short) ifindex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(final InetAddress dst, final byte prefix, final String ifname) {
        delete0(dst, prefix, ifname);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(final InetAddress dst, final byte prefix, final int ifindex) {
        delete0(dst, prefix, (short) ifindex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Route> routes() {
        return getAll0(AF_UNSPEC);
    }

    public static DarwinNetworkRoutingTable get() {
        return INSTANCE;
    }

    /**
     * Add a new route.
     *
     * @param dst    the destination network or host
     * @param prefix the netmask prefix length
     * @param gw     the gateway used for route packets, the specified gateway must be reachable first.
     * @param ifname the interface name to bound
     */
    public static void add0(final InetAddress dst, final int prefix,
                     final InetAddress gw, final String ifname) {
        final short ifindex = null != ifname ? (short) if_nametoindex0(ifname) : 0;
        add0(dst, prefix, gw, ifindex);
    }

    /**
     * Add a new route.
     *
     * @param dst     the destination network or host
     * @param prefix  the netmask prefix length
     * @param gw      the gateway used for route packets, the specified gateway must be reachable first.
     * @param ifindex the interface index to bound
     */
    private static void add0(final InetAddress dst, final int prefix,
                             final InetAddress gw, final short ifindex) {
        final InetAddress netmask = cidrToNetmaskAddress(dst, prefix);
        route0((byte) com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTM_ADD, dst, netmask, gw, ifindex);
    }

    /**
     * Delete a route.
     *
     * @param dst    the destination network or host
     * @param prefix the netmask prefix length
     * @param ifname the interface name to bound
     */
    private static void delete0(final InetAddress dst, final int prefix, final String ifname) {
        final short ifindex = null != ifname ? (short) if_nametoindex0(ifname) : 0;
        delete0(dst, prefix, ifindex);
    }

    /**
     * Delete a route.
     *
     * @param dst     the destination network or host
     * @param prefix  the netmask prefix length
     * @param ifindex the interface index to bound
     */
    private static void delete0(final InetAddress dst, final int prefix, final short ifindex) {
        final InetAddress netmask = cidrToNetmaskAddress(dst, prefix);
        route0((byte) com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTM_DELETE, dst, netmask, null, ifindex);
    }

    /**
     * Get a route.
     *
     * @param dst     the destination network or host
     * @param prefix  the netmask prefix length
     * @param ifindex the interface index to bound
     */
    private static void get0(final InetAddress dst, final int prefix, final short ifindex) {
        final InetAddress netmask = cidrToNetmaskAddress(dst, prefix);
        route0((byte) com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTM_GET, dst, netmask, null, ifindex);
    }

    /**
     * Manipulates the kernel's IP routing tables.
     *
     * @param rtm_type  RTM_ADD | RTM_DELETE | RTM_GET
     * @param dst       the destination network or host
     * @param netmask   the netmask
     * @param gw        the gateway used for route packets, the specified gateway must be reachable first.
     * @param rtm_index the interface index to bound
     */
    private static void route0(final byte rtm_type,
                               final InetAddress dst, final InetAddress netmask,
                               final InetAddress gw, final short rtm_index) {
        final int rtmsgMaxSize = RT_MSGHDR_SIZE + SOCKADDR_IN6_SIZE * 3 + SOCKADDR_DL_SIZE;
        /*-
         * 当前只写一条路由；循环体与 rtmsgMaxSize * N 的缓冲结构为将来批量添加路由预留，
         * 届时只需把常量 1 替换为实际条数即可。
         */
        final int count = 1;

        int bytesWritten = 0;
        final Memory buffer = new Memory((long) rtmsgMaxSize * count);
        try {
            for (int i = 0; i < count; i++) {
                final Pointer ptr = buffer.share(bytesWritten);
                final com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.rt_msghdr rtm = new com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.rt_msghdr(ptr);
                rtm.rtm_version = com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTM_VERSION;
                rtm.rtm_type = rtm_type;
                rtm.rtm_flags = com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTF_UP | com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTF_GATEWAY | com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTF_STATIC;
                rtm.rtm_pid = 0;
                rtm.rtm_seq = i + 1;

                int offset = rtm.size();
                if (null != dst) {
                    rtm.rtm_addrs |= com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTA_DST;
                    offset += writeSockAddrIn(ptr.share(offset), dst);
                }
                if (null != gw) {
                    rtm.rtm_addrs |= com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTA_GATEWAY;
                    offset += writeSockAddrIn(ptr.share(offset), gw);
                }
                if (null != netmask) {
                    rtm.rtm_addrs |= com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTA_NETMASK;
                    offset += writeSockAddrIn(ptr.share(offset), netmask);
                }
                if (rtm_index != 0) {
                    rtm.rtm_addrs |= com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTA_IFP;
                    rtm.rtm_index = rtm_index;
                    offset += writeSockAddrDl(ptr.share(offset), rtm_index);
                }

                rtm.rtm_msglen = (short) offset;
                rtm.write();
                bytesWritten += offset;
            }

            final int fd = LIBC.socket(AF_ROUTE, SOCK_RAW, AF_UNSPEC);
            if (fd < 0) {
                throwLastErrorException(Native.getLastError());
            }
            try {
                final int actualBytesWritten = LIBC.write(fd, buffer, bytesWritten);
                if (actualBytesWritten != bytesWritten) {
                    throwLastErrorException(Native.getLastError());
                }
            } finally {
                LIBC.close(fd);
            }
        } finally {
            buffer.close();
        }
    }

    /**
     * Write a inet address to pointer.
     *
     * @param ptr     the pointer
     * @param address the inet address
     * @return the bytes written
     */
    private static int writeSockAddrIn(final Pointer ptr, final InetAddress address) {
        if (address instanceof Inet4Address) {
            final sockaddr_in in = new sockaddr_in(ptr);
            writeSockAddr4(in, (Inet4Address) address);
            in.write();
            return in.size();
        } else if (address instanceof Inet6Address) {
            final sockaddr_in6 in = new sockaddr_in6(ptr);
            writeSockAddr6(in, (Inet6Address) address);
            in.write();
            return in.size();
        }
        throw new UnsupportedOperationException();
    }

    /**
     * Write a interface index (Link-Level sockaddr) to pointer.
     *
     * @param ptr     the pointer
     * @param ifindex the interface index
     * @return the bytes written
     */
    private static int writeSockAddrDl(final Pointer ptr, final int ifindex) {
        final sockaddr_dl dl = new sockaddr_dl(ptr);
        dl.sdl_len = (byte) dl.size();
        dl.sdl_family = AF_LINK;
        dl.sdl_index = (short) ifindex;
        dl.write();
        return dl.size();
    }

    /**
     * Get a list of all route.
     * <p/>
     * <pre><code>netstat -nra</code></pre>
     *
     * @param family the address family, AF_INET | AF_INET6 | AF_UNSPEC
     * @return the list of all route
     */
    private static List<Route> getAll0(final int family) {
        /*-
         * net sub-system, route address family, unspec protocol, all interface, dump route table, reserved
         */
        final IntByReference sizeRef = new IntByReference(0);
        final int[] mib = {CTL_NET, AF_ROUTE, 0, family, NET_RT_DUMP, 0};
        if (LIBC.sysctl(mib, mib.length, NULL, sizeRef, NULL, new IntByReference(0)) < 0) {
            throwLastErrorException(Native.getLastError());
        }

        final Memory buf = new Memory(sizeRef.getValue());
        try {
            if (LIBC.sysctl(mib, mib.length, buf, sizeRef, NULL, new IntByReference(0)) < 0) {
                throwLastErrorException(Native.getLastError());
            }

            final List<Route> routes = Lists.newLinkedList();
            final int size = sizeRef.getValue();
            for (int bytesRead = 0; bytesRead < size; ) {
                final com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.rt_msghdr rtm = new com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.rt_msghdr(buf.share(bytesRead, size - bytesRead));
                rtm.read();

                if ((rtm.rtm_flags & com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTF_UP) != 0 && rtm.rtm_type == com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTM_GET) {
                    final Route entry = parseRouteEntry(rtm);
                    if (null != entry) {
                        routes.add(entry);
                    }
                }

                // move pointer to next route entry.
                bytesRead += rtm.rtm_msglen;
            }
            return routes;
        } finally {
            buf.close();
        }
    }

    /**
     * Parse route entry.
     *
     * @param rtm the route message
     * @return the route entry
     */
    private static Route parseRouteEntry(final com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.rt_msghdr rtm) {
        final Pointer ptr = rtm.getPointer();

        int bytesRead = rtm.size();

        final Pointer[] tab = new Pointer[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_MAX];
        for (int i = 0; i < com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_MAX && bytesRead < rtm.rtm_msglen; i++) {
            if ((rtm.rtm_addrs & (1 << i)) != 0) {
                final int len = align(ptr.getByte(bytesRead) & 0xFF, 4);
                if (len > 0) {
                    tab[i] = ptr.share(bytesRead, rtm.rtm_msglen - bytesRead);
                } else {
                    // skip 4-bytes pad.
                }
                // move pointer to next sockaddr.
                bytesRead += roundUp(len, 4);
            }
        }

        final InetAddress dst = null != tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_DST] ? parseInetAddress(tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_DST]) : null;
        final InetAddress gw = null != tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_GATEWAY] ? parseInetAddress(tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_GATEWAY]) : null;

        // AF_LINK 作为 RTAX_DST 的条目（例如 link-scoped route），parseInetAddress 返回 null，
        // 后续 Route 使用端会 NPE；此类条目无意义，直接跳过
        if (null == dst) {
            return null;
        }

        final int cidr = null != tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_NETMASK]
                ? parseNetmask(tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_NETMASK])
                : (rtm.rtm_flags & com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTF_HOST) != 0
                ? dst.getAddress().length * Byte.SIZE
                : 0;

        int ifindex = rtm.rtm_index;
        if (tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_IFP] != null) {
            // IFP never exists ?
            sockaddr_dl d = new sockaddr_dl(tab[com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Route.RTAX_IFP]);
            d.read();
            ifindex = d.sdl_index;
        }

        final String dev = if_indextoname(ifindex);
        return new Route(dst, cidr, gw, dev, 0);
    }

    /**
     * Parse a inet address from pointer.
     *
     * @param ptr the pointer
     * @return the inet address
     */
    private static InetAddress parseInetAddress(final Pointer ptr) {
        final int len = ptr.getByte(0) & 0xFF;
        final int family = ptr.getByte(1) & 0xFF;
        if (AF_INET == family) {
            final sockaddr_in in = new sockaddr_in(ptr.share(0, len));
            in.read();
            return toInet4Address(in);
        } else if (AF_INET6 == family) {
            final sockaddr_in6 in6 = new sockaddr_in6(ptr.share(0, len));
            in6.read();
            return toInet6Address(in6);
        } else if (AF_LINK == family) {
            final sockaddr_dl dl = new sockaddr_dl(ptr.share(0, len));
            dl.read();
            // FIXME
            // System.out.println("LINK-" + dl.sdl_index);
            return null;
        } else {
            throw new UnsupportedOperationException("Unknown family: " + family);
        }
    }

    /**
     * Parse net mask.
     *
     * @param ptr the pointer
     * @return the net mask
     */
    private static int parseNetmask(final Pointer ptr) {
        final int len = ptr.getByte(0) & 0xFF;
        if (len <= 0) {
            return 0;
        }

        final int family = ptr.getByte(1) & 0xFF;
        if (AF_INET == family) {
            final sockaddr_in in = new sockaddr_in(ptr.share(0, len));
            in.read();
            return NetUtils2.binmaskToCidr(in.sin_addr);
        } else if (AF_INET6 == family) {
            final sockaddr_in6 in6 = new sockaddr_in6(ptr.share(0, len));
            in6.read();
            return NetUtils2.binmaskToCidr(in6.sin6_addr);
        } else if (AF_LINK == family) {
            final sockaddr_dl dl = new sockaddr_dl(ptr.share(0, len));
            dl.read();
            throw new UnsupportedOperationException("Netmask family AF_LINK: " + dl.sdl_index);
        } else if (len <= 8 && family == 0xFF) {
            /*-
             * IPv4 netmask:
             * <pre>
             * struct sockaddr_in-like {
             *   __uint8_t    sin_len;     --> len
             *   sa_family_t  sin_family;  --> -1 (uint8)
             *   in_port_t    sin_port;   --> {-1, -1} (short)
             *   char         sin_addr[n]; --> netmask non-zero bytes, n <= 8
             * }
             * </pre>
             */
            assert -1 == ptr.getByte(2);
            assert -1 == ptr.getByte(3);

            final byte[] addr = ptr.getByteArray(4, len - 4);
            final byte[] bytes = Arrays.copyOf(addr, 4);
            return NetUtils2.binmaskToCidr(bytes);
        } else if (len <= 16 && family == 0xFF) {
            /*-
             * IPv6 netmask:
             * <pre>
             * struct sockaddr_in6-like {
             *   __uint8_t   sin6_len;      --> len
             *   sa_family_t sin6_family;   --> -1
             *   in_port_t   sin6_port;     --> {-1, -1}
             *   __uint32_t  sin6_flowinfo; --> {-1, -1, -1, -1}
             *   char        sin6_addr[n];  --> netmask non-zero bytes, n <= 16
             * }
             * </pre>
             */
            assert -1 == ptr.getByte(2);
            assert -1 == ptr.getByte(3);
            // flowinfo
            assert -1 == ptr.getByte(4);
            assert -1 == ptr.getByte(5);
            assert -1 == ptr.getByte(6);
            assert -1 == ptr.getByte(7);

            final byte[] addr = ptr.getByteArray(8, len - 8);
            final byte[] bytes = Arrays.copyOf(addr, 16);
            return NetUtils2.binmaskToCidr(bytes);
        } else {
            throw new UnsupportedOperationException("Unknown family: " + family);
        }
    }

    private static int roundUp(final int len, final int align) {
        return ((len) > 0 ? (1 + (((len) - 1) | (align - 1))) : align);
    }

    private static int align(final int len, final int align) {
        return (len + align - 1) & ~(align - 1);
    }

    private static int if_nametoindex0(final String ifname) {
        final int index = LIBC.if_nametoindex(ifname);
        if (0 == index) {
            throwLastErrorException(Native.getLastError());
        }
        return index;
    }

    private static String if_indextoname(final int ifindex) {
        final byte[] buf = new byte[IFNAMSIZ];
        LIBC.if_indextoname(ifindex, buf);
        return Native.toString(buf);
    }

}