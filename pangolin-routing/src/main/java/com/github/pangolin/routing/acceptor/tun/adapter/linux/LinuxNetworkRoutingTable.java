package com.github.pangolin.routing.acceptor.tun.adapter.linux;

import com.github.pangolin.routing.acceptor.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Netlink;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.RtNetlink;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

import static com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.If.IFNAMSIZ;
import static com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxUtils.throwLastErrorException;
import static com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Socket.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2.toInetAddress;

public class LinuxNetworkRoutingTable extends NetworkRoutingTable {

    /**
     * Lib C instance.
     */
    private static final LibC LIBC = LibC.INSTANTCE;

    /**
     * Placeholder pointer to help avoid auto-allocation of memory where a
     * Structure needs a valid pointer but want to avoid actually reading from it.
     */
    static final Pointer PLACEHOLDER_MEMORY = new Pointer(0) {
        @Override
        public Pointer share(final long offset, final long sz) {
            return this;
        }
    };

    static final int NLMSGHDR_SIZE = new Netlink.nlmsghdr(PLACEHOLDER_MEMORY).size();
    static final int RTATTR_SIZE = new RtNetlink.rtattr(PLACEHOLDER_MEMORY).size();

    private static final int RTMSG_SIZE = new RtNetlink.rtmsg(PLACEHOLDER_MEMORY).size();

    /**
     * Default instance.
     */
    private static final LinuxNetworkRoutingTable INSTANCE = new LinuxNetworkRoutingTable();

    /**
     * Private constructor.
     */
    private LinuxNetworkRoutingTable() {
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
        return getAll0((byte) AF_UNSPEC);
    }

    public static LinuxNetworkRoutingTable get() {
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
    static void add0(final InetAddress dst, final int prefix,
                     final InetAddress gw, final String ifname) {
        final short ifindex = null != ifname ? (short) LinuxNetworkInterface.if_nametoindex0(ifname) : 0;
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
        route0((byte) RtNetlink.RTM_NEWROUTE, dst, prefix, gw, ifindex);
    }

    /**
     * Delete a route.
     *
     * @param dst    the destination network or host
     * @param prefix the netmask prefix length
     * @param ifname the interface name to bound
     */
    private static void delete0(final InetAddress dst, final int prefix, final String ifname) {
        final short ifindex = null != ifname ? (short) LinuxNetworkInterface.if_nametoindex0(ifname) : 0;
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
        route0((short) RtNetlink.RTM_DELROUTE, dst, prefix, null, ifindex);
    }

    /**
     * Get a route.
     *
     * @param dst     the destination network or host
     * @param prefix  the netmask prefix length
     * @param ifindex the interface index to bound
     */
    private static void get0(final InetAddress dst, final int prefix, final short ifindex) {
        route0((short) RtNetlink.RTM_GETROUTE, dst, prefix, null, ifindex);
    }

    /**
     * Manipulates the kernel's IP routing tables.
     *
     * @param nlmsg_type RTM_NEWROUTE | RTM_DELROUTE | RTM_GETROUTE
     * @param dst        the destination network or host
     * @param prefix     the netmask prefix length
     * @param gw         the gateway used for route packets, the specified gateway must be reachable first.
     * @param ifindex    the interface index to bound
     */
    private static <A extends InetAddress> void route0(final short nlmsg_type,
                                                       final A dst, final int prefix,
                                                       final A gw, final int ifindex) {
        byte family;
        if (dst instanceof Inet4Address) {
            family = AF_INET;
        } else if (dst instanceof Inet6Address) {
            family = AF_INET6;
        } else {
            throw new IllegalArgumentException();
        }
        final byte[] gwBytes = gw == null ? new byte[0] : gw.getAddress();
        route0(nlmsg_type, family, dst.getAddress(), prefix, gwBytes, ifindex);
    }

    /**
     * Manipulates the kernel's IP routing tables.
     *
     * @param nlmsg_type RTM_NEWROUTE | RTM_DELROUTE | RTM_GETROUTE
     * @param family     the address family
     * @param dst        the destination network or host
     * @param prefix     the netmask prefix length
     * @param gw         the gateway used for route packets, the specified gateway must be reachable first.
     * @param ifindex    the interface index to bound
     */
    private static void route0(final short nlmsg_type, final byte family,
                               final byte[] dst, final int prefix,
                               final byte[] gw, final int ifindex) {
        assert 0 == gw.length || dst.length == gw.length;

        final int sockfd = bind();
        try {
            final int wCapacity = NLMSGHDR_SIZE + RTMSG_SIZE
                    + rtaAlign(RTATTR_SIZE + dst.length) * 2
                    + rtaAlign(RTATTR_SIZE + 4);

            final Memory wBuf = new Memory(wCapacity);
            try {
                int bytesWritten = 0;
                final Netlink.nlmsghdr nlm = new Netlink.nlmsghdr(wBuf.share(bytesWritten));
                // nlmsg_len 延迟到写完所有 rtattr 后再回填（BUG-2）
                short flags = (short) (Netlink.NLM_F_REQUEST | Netlink.NLM_F_ACK);
                if (RtNetlink.RTM_NEWROUTE == nlmsg_type) {
                    flags |= Netlink.NLM_F_CREATE;
                }
                nlm.nlmsg_flags = flags;
                nlm.nlmsg_type = nlmsg_type;
                nlm.nlmsg_seq = 1;
                nlm.nlmsg_pid = LIBC.getpid();
                bytesWritten += nlm.size();

                final RtNetlink.rtmsg rtm = new RtNetlink.rtmsg(wBuf.share(bytesWritten));
                rtm.rtm_family = family;
                rtm.rtm_dst_len = (byte) prefix;

                rtm.rtm_table = (byte) RtNetlink.RT_TABLE_MAIN;
                rtm.rtm_protocol = RtNetlink.RTPROT_STATIC;
                rtm.rtm_scope = RtNetlink.RT_SCOPE_UNIVERSE;
                rtm.rtm_type = RtNetlink.RTN_UNICAST;
                rtm.write();
                bytesWritten += rtm.size();

                bytesWritten += writeBytesRtAttr(wBuf.share(bytesWritten), (short) RtNetlink.RTA_DST, dst);
                if (gw.length > 0) {
                    bytesWritten += writeBytesRtAttr(wBuf.share(bytesWritten), (short) RtNetlink.RTA_GATEWAY, gw);
                }
                if (0 != ifindex) {
                    bytesWritten += writeIntRtAttr(wBuf.share(bytesWritten), (short) RtNetlink.RTA_OIF, ifindex);
                }

                nlm.nlmsg_len = bytesWritten;
                nlm.write();

                if (LIBC.send(sockfd, nlm.getPointer(), bytesWritten, 0) < 0) {
                    LinuxUtils.throwLastErrorException(Native.getLastError());
                }

                checkAck(sockfd);
            } finally {
                wBuf.close();
            }
        } finally {
            LIBC.close(sockfd);
        }
    }

    /**
     * Receive and parse the ACK from a netlink request. Throws on non-zero error code.
     */
    private static void checkAck(final int sockfd) {
        final Memory rBuf = new Memory(8192);
        try {
            final int size = LIBC.recv(sockfd, rBuf, (int) rBuf.size(), 0);
            if (size < 0) {
                LinuxUtils.throwLastErrorException(Native.getLastError());
            }
            if (size < NLMSGHDR_SIZE) {
                return;
            }
            final Netlink.nlmsghdr nlmR = new Netlink.nlmsghdr(rBuf);
            nlmR.read();
            if (Netlink.NLMSG_ERROR == nlmR.nlmsg_type) {
                // struct nlmsgerr { int error; struct nlmsghdr msg; }
                final int err = rBuf.getInt(nlmR.size());
                if (0 != err) {
                    LinuxUtils.throwLastErrorException(-err);
                }
            }
        } finally {
            rBuf.close();
        }
    }

    private static int writeIntRtAttr(final Pointer ptr, final short type, final int ifindex) {
        final RtNetlink.rtattr rta = new RtNetlink.rtattr(ptr);
        rta.rta_len = (short) (rta.size() + 4);
        rta.rta_type = type;
        rta.write();

        ptr.setInt(rta.size(), ifindex);
        return rtaAlign(rta.rta_len);
    }

    /**
     * Write the bytes rtattr to pointer.
     *
     * @param ptr   the pointer
     * @param bytes the bytes to write
     * @return the bytes written
     */
    static int writeBytesRtAttr(final Pointer ptr, final short rtaType, final byte[] bytes) {
        final RtNetlink.rtattr rta = new RtNetlink.rtattr(ptr);
        rta.rta_len = (short) (rta.size() + bytes.length);
        rta.rta_type = rtaType;
        rta.write();

        ptr.write(rta.size(), bytes, 0, bytes.length);
        return rtaAlign(rta.rta_len);
    }

    /**
     * Get a list of all route.
     * <p/>
     * <pre><code>netstat -nra</code></pre>
     *
     * @param family the address family, AF_INET | AF_INET6 | AF_UNSPEC
     * @return the list of all route
     */
    private static List<Route> getAll0(final byte family) {
        final int sockfd = bind();
        try {
            final int wLen = NLMSGHDR_SIZE + RTMSG_SIZE;
            final Memory wBuf = new Memory(nlmsgAlign(wLen));
            try {
                int bytesWritten = 0;
                final Netlink.nlmsghdr nlm = new Netlink.nlmsghdr(wBuf.share(bytesWritten));
                nlm.nlmsg_len = wLen;
                nlm.nlmsg_type = RtNetlink.RTM_GETROUTE;
                nlm.nlmsg_flags = Netlink.NLM_F_REQUEST | Netlink.NLM_F_DUMP;
                nlm.nlmsg_seq = 1;
                nlm.nlmsg_pid = LIBC.getpid();
                nlm.write();
                bytesWritten += nlm.size();

                final RtNetlink.rtmsg rtm = new RtNetlink.rtmsg(wBuf.share(bytesWritten));
                rtm.rtm_family = family;
                rtm.rtm_table = (byte) RtNetlink.RT_TABLE_MAIN;
                /*-
                rt.rtm_protocol = RTPROT_STATIC;
                rt.rtm_scope = RT_SCOPE_UNIVERSE;
                rt.rtm_type = RTN_UNICAST;
                */
                rtm.write();
                bytesWritten += rtm.size();

                if (LIBC.send(sockfd, wBuf, bytesWritten, 0) < 0) {
                    LinuxUtils.throwLastErrorException(Native.getLastError());
                }

                final List<Route> routes = Lists.newLinkedList();
                final Memory rBuf = new Memory(8192);
                try {
                    int size;
                    while ((size = LIBC.recv(sockfd, rBuf, (int) rBuf.size(), 0)) > 0) {
                        /*-
                         * Parse nlmsg.
                         */
                        for (int bytesRead = 0; bytesRead < size; ) {
                            final Pointer ptr = rBuf.share(bytesRead, size - bytesRead);
                            final Netlink.nlmsghdr nlmR = new Netlink.nlmsghdr(ptr);
                            nlmR.read();

                            if (nlmR.nlmsg_type == Netlink.NLMSG_ERROR) {
                                return routes;
                            }
                            if (nlmR.nlmsg_type == Netlink.NLMSG_DONE) {
                                return routes;
                            }
                            if (nlmR.nlmsg_type == RtNetlink.RTM_NEWROUTE) {
                                final int len = nlmR.nlmsg_len - nlmR.size();
                                final RtNetlink.rtmsg rtmR = new RtNetlink.rtmsg(ptr.share(nlmR.size(), len));
                                rtmR.read();

                                routes.add(parseRouteEntry(rtmR, len));
                            }
                            bytesRead += nlmsgAlign(nlmR.nlmsg_len);
                        }
                    }
                    return routes;
                } finally {
                    rBuf.close();
                }
            } finally {
                wBuf.close();
            }
        } finally {
            LIBC.close(sockfd);
        }
    }

    static int bind() {
        final int sockfd = LIBC.socket(AF_NETLINK, SOCK_RAW, Netlink.NETLINK_ROUTE);
        try {
            final Netlink.sockaddr_nl localAddr = new Netlink.sockaddr_nl();
            localAddr.nl_family = AF_NETLINK;
            localAddr.nl_pid = LIBC.getpid();

            if (LIBC.bind(sockfd, localAddr, localAddr.size()) < 0) {
                LinuxUtils.throwLastErrorException("cannot open netlink socket");
            }
            return sockfd;
        } catch (final RuntimeException ex) {
            LIBC.close(sockfd);
            throw ex;
        }
    }

    /**
     * Parse route entry.
     *
     * @param rtm the route message
     * @param len the length of rtm
     * @return the route entry
     */
    private static Route parseRouteEntry(final RtNetlink.rtmsg rtm, final int len) {
        final Pointer ptr = rtm.getPointer();
        final RtNetlink.rtattr[] tab = new RtNetlink.rtattr[RtNetlink.RTA_MAX + 1];
        for (int bytesRead = rtm.size(); bytesRead < len; ) {
            final RtNetlink.rtattr rta = new RtNetlink.rtattr(ptr.share(bytesRead, len - bytesRead));
            rta.read();

            if (rta.rta_type <= RtNetlink.RTA_MAX) {
                tab[rta.rta_type] = rta;
            }
            bytesRead += rtaAlign(rta.rta_len);
        }

        final InetAddress def = AF_INET == rtm.rtm_family
                ? toInetAddress(new byte[4])
                : AF_INET6 == rtm.rtm_family
                ? toInetAddress(new byte[16])
                : null;

        final int cidr = rtm.rtm_dst_len & 0xFF;
        final InetAddress dst = null != tab[RtNetlink.RTA_DST] ? parseInetAddress(tab[RtNetlink.RTA_DST]) : def;
        final InetAddress gw = null != tab[RtNetlink.RTA_GATEWAY] ? parseInetAddress(tab[RtNetlink.RTA_GATEWAY]) : def;
        final int ifindex = null != tab[RtNetlink.RTA_OIF] ? parseInt(tab[RtNetlink.RTA_OIF]) : 0;
        final int metrics = null != tab[RtNetlink.RTA_PRIORITY] ? parseInt(tab[RtNetlink.RTA_PRIORITY]) : 0;

        final String ifname = 0 != ifindex ? if_indextoname0(ifindex) : null;
        return new Route(dst, cidr, gw, ifname, metrics);
    }

    /**
     * Parse a inet address from the route attribute.
     *
     * @param rta the route attribute
     * @return the inet address
     */
    private static InetAddress parseInetAddress(final RtNetlink.rtattr rta) {
        final byte[] addr = rta.getPointer().getByteArray(rta.size(), rta.rta_len - rta.size());
        return toInetAddress(addr);
    }

    /**
     * Parse a int value from the route attribute.
     *
     * @param rta the route attribute
     * @return the int value
     */
    private static int parseInt(final RtNetlink.rtattr rta) {
        return rta.getPointer().getInt(rta.size());
    }

    static int nlmsgAlign(final int len) {
        return _align(len, Netlink.NLMSG_ALIGNTO);
    }

    static int rtaAlign(final int len) {
        return _align(len, RtNetlink.RTA_ALIGNTO);
    }

    private static int _align(final int len, final int align) {
        return (len + align - 1) & ~(align - 1);
    }

    private static String if_indextoname0(final int ifindex) {
        final byte[] _buf = new byte[IFNAMSIZ];
        LIBC.if_indextoname(ifindex, _buf);
        return Native.toString(_buf);
    }

}