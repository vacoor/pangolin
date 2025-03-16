package com.github.pangolin.routing.server.tun.adapter.linux;

import com.github.pangolin.routing.server.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.server.tun.adapter.util.NetUtils2;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.IFNAMSIZ;
import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxNetworkInterface.if_nametoindex0;
import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxUtils.throwLastErrorException;
import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxUtils.toInet4Address;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.INSTANTCE;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.toInetAddress;

public class LinuxNetworkRoutingTable extends NetworkRoutingTable {

    private static final LibC LIBC = LibC.INSTANTCE;

    private static final LinuxNetworkRoutingTable INSTANCE = new LinuxNetworkRoutingTable();

    private LinuxNetworkRoutingTable() {
    }

    @Override
    public void add(final InetAddress dst, final byte prefix, final InetAddress gw, final String ifname, final int metric) {

    }

    @Override
    public void add(final InetAddress dst, final byte prefix, final InetAddress gw, final int ifindex, final int metric) {

    }

    @Override
    public void delete(final InetAddress dst, final byte prefix, final String ifname) {

    }

    @Override
    public void delete(final InetAddress dst, final byte prefix, final int ifindex) {

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

    public static void add(final String ifname, final Inet4Address addr, final int prefix, final Inet4Address gwAddr, final boolean delete) {
        int ifindex = if_nametoindex0(ifname);
        add(ifindex, addr, prefix, gwAddr, delete);
    }

    public static void add(final int ifindex, final Inet4Address addr, final int prefix, final Inet4Address gwAddr, final boolean delete) {
        final byte[] dst = addr.getAddress();
        final byte[] gw = gwAddr.getAddress();
        final int sockfd = LIBC.socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
        try {
            final sockaddr_nl localAddr = new sockaddr_nl();
            localAddr.nl_family = AF_NETLINK;
            localAddr.nl_pid = INSTANTCE.getpid();

            if (INSTANTCE.bind(sockfd, localAddr, localAddr.size()) < 0) {
                throwLastErrorException(Native.getLastError());
            }

            int msgSize = 44 + 8; // nlmsghdr(16) + rtmsg(12) + dst rtattr(4) + dst addr(4) + gw rtattr(4) + gw addr(4) + oif rtattr(4) + ifindex(4)

            // 初始化消息内存
            final Memory buffer = new Memory(msgSize);
            int offset = 0;

            final nlmsghdr hl = new nlmsghdr(buffer.share(offset));
            hl.nlmsg_len = msgSize;
            hl.nlmsg_flags = NLM_F_REQUEST | NLM_F_CREATE;
            hl.nlmsg_type = (short) (!delete ? RTM_NEWROUTE : RTM_DELROUTE);
            hl.write();
            offset += hl.size();

            final rtmsg rt = new rtmsg(buffer.share(offset));
            rt.rtm_family = AF_INET;
            rt.rtm_dst_len = (byte) prefix;
            rt.rtm_table = (byte) RT_TABLE_MAIN;
            rt.rtm_protocol = RTPROT_STATIC;
            rt.rtm_scope = RT_SCOPE_UNIVERSE;
            rt.rtm_type = RTN_UNICAST;
            rt.write();
            offset += rt.size();

            // 填充rtattr（DST 地址）
            offset += writeSockAddrIn(buffer.share(offset), (short) RTA_DST, dst);


            // 填充rtattr（GATEWAY 地址）
            offset += writeSockAddrIn(buffer.share(offset), (short) RTA_GATEWAY, gw);

            // 填充rtattr（Interface）
            offset += writeInt(buffer.share(offset), (short) RTA_OIF, ifindex);

            final int written = LIBC.send(sockfd, hl.getPointer(), offset, 0);
            if (written < 0) {
                throwLastErrorException(Native.getLastError());
            }
        } finally {
            LIBC.close(sockfd);
        }
    }

    private static List<Route> getAll0(final byte family) {
        final int sockfd = LIBC.socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
        try {
            final sockaddr_nl localAddr = new sockaddr_nl();
            localAddr.nl_family = AF_NETLINK;
            localAddr.nl_pid = INSTANTCE.getpid();

            if (INSTANTCE.bind(sockfd, localAddr, localAddr.size()) < 0) {
                throwLastErrorException(Native.getLastError());
            }

            int msgSize = 16 + 12; // nlmsghdr(16) + rtmsg(12)

            // 初始化消息内存
            final Memory buffer = new Memory(msgSize);

            int bytesWritten = 0;
            final nlmsghdr hl = new nlmsghdr(buffer.share(bytesWritten));
            hl.nlmsg_len = msgSize;
            hl.nlmsg_type = RTM_GETROUTE;
            hl.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
            hl.nlmsg_seq = 1;
            hl.nlmsg_pid = LIBC.getpid();
            hl.write();
            bytesWritten += hl.size();

            final rtmsg rt = new rtmsg(buffer.share(bytesWritten));
//            rt.rtm_family = AF_INET;
            rt.rtm_family = family;
//            rt.rtm_dst_len = (byte) 0;
//            rt.rtm_table = (byte) RT_TABLE_MAIN;
//            rt.rtm_protocol = RTPROT_STATIC;
//            rt.rtm_scope = RT_SCOPE_UNIVERSE;
//            rt.rtm_type = RTN_UNICAST;
            rt.write();
            bytesWritten += rt.size();

            final int written = LIBC.send(sockfd, buffer, bytesWritten, 0);
            if (written < 0) {
                throwLastErrorException(Native.getLastError());
            }

            final List<Route> routes = Lists.newLinkedList();
            final Memory buf = new Memory(2096);
            int size;
            while ((size = LIBC.recv(sockfd, buf, (int) buf.size(), 0)) > 0) {
                /*-
                 * Parse nlmsg.
                 */
                for (int bytesRead = 0; bytesRead < size; ) {
                    final Pointer ptr = buf.share(bytesRead, size - bytesRead);
                    final nlmsghdr nlh = new nlmsghdr(ptr);
                    nlh.read();

                    if (nlh.nlmsg_type == NLMSG_ERROR) {
                        return routes;
                    }
                    if (nlh.nlmsg_type == NLMSG_DONE) {
                        return routes;
                    }
                    if (nlh.nlmsg_type == RTM_NEWROUTE) {
                        final int len = nlh.nlmsg_len - nlh.size();
                        final rtmsg rtm = new rtmsg(ptr.share(nlh.size(), len));
                        rtm.read();

                        routes.add(parseRouteEntry(rtm, len));
                    }
                    bytesRead += nlh.nlmsg_len;
                }
            }
            return routes;
        } finally {
            LIBC.close(sockfd);
        }
    }

    private static Route parseRouteEntry(final rtmsg rtm, final int len) {
        final Pointer ptr = rtm.getPointer();

        final rtattr[] tab = new rtattr[RTA_MAX + 1];
        for (int bytesRead = rtm.size(); bytesRead < len;) {
            rtattr rta = new rtattr(ptr.share(bytesRead, len - bytesRead));
            rta.read();

            if (rta.rta_type <= RTA_MAX) {
                tab[rta.rta_type] = rta;
            }
            bytesRead += align(rta.rta_len, 4);
        }

        final InetAddress def = AF_INET == rtm.rtm_family
                ? toInetAddress(new byte[4])
                : AF_INET6 == rtm.rtm_family
                ? toInetAddress(new byte[16])
                : null;

        final int cidr = rtm.rtm_dst_len;
        final InetAddress dst = null != tab[RTA_DST] ? parseInetAddress(tab[RTA_DST]) : def;
        final InetAddress gw = null != tab[RTA_GATEWAY] ? parseInetAddress(tab[RTA_GATEWAY]) : def;
        final int ifindex = null != tab[RTA_OIF] ? parseInt(tab[RTA_OIF]) : 0;
        final int metrics = null != tab[RTA_PRIORITY] ? parseInt(tab[RTA_PRIORITY]) : -1;

        final String ifname = 0 != ifindex ? if_indextoname0(ifindex) : null;
        System.out.printf("%s/%s\t%s\t%s\t%s\n", dst, cidr, gw, ifname, metrics);
        return new Route(dst, cidr, gw, ifname, metrics);
    }

    private static rtattr[] parseRtattr(final Pointer ptr, final int rtattr_len) {
        final rtattr[] tab = new rtattr[RTA_MAX + 1];
        for (int offset = 0; offset < rtattr_len; ) {
            rtattr rta = new rtattr(ptr.share(offset));
            rta.read();

            if (rta.rta_type <= RTA_MAX) {
                tab[rta.rta_type] = rta;
            }
            offset += align(rta.rta_len, 4);
        }
        return tab;
    }

    private static InetAddress parseInetAddress(final rtattr rta) {
        final byte[] addr = rta.getPointer().getByteArray(rta.size(), rta.rta_len - rta.size());
        return toInetAddress(addr);
    }

    private static int parseInt(final rtattr rta) {
        return rta.getPointer().getInt(rta.size());
    }

    private static int align(final int len, final int align) {
        return (len + align - 1) & ~(align - 1);
    }

    static int writeSockAddrIn(final Pointer ptr, final short rtaType, final byte[] addr) {
        rtattr rta = new rtattr(ptr);
        rta.rta_len = (byte) (rta.size() + addr.length);
        rta.rta_type = rtaType;
        rta.write();

        ptr.write(rta.size(), addr, 0, addr.length);
        return rta.rta_len;
    }

    private static int writeInt(final Pointer ptr, final short type, final int ifindex) {
        rtattr rta = new rtattr(ptr);
        rta.rta_len = (byte) (rta.size() + 4);
        rta.rta_type = type; // (short) RTA_OIF;
        rta.write();
//        offset += rta.size();

        ptr.setInt(rta.size(), ifindex);
//        offset += 4;
        return rta.size() + 4;
    }

    private static String if_indextoname0(final int ifindex) {
        final byte[] _buf = new byte[IFNAMSIZ];
        LIBC.if_indextoname(ifindex, _buf);
        return Native.toString(_buf);
    }

}