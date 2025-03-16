package com.github.pangolin.routing.server.tun.adapter.linux;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.IFNAMSIZ;
import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxNetworkInterface.if_nametoindex0;
import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxUtils.throwLastErrorException;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.NETLINK_ROUTE;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.NLMSG_DONE;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.NLMSG_ERROR;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.NLM_F_CREATE;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.NLM_F_DUMP;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.NLM_F_REQUEST;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.nlmsghdr;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.sockaddr_nl;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTA_DST;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTA_GATEWAY;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTA_MAX;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTA_METRICS;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTA_OIF;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTA_PRIORITY;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTM_DELROUTE;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTM_GETROUTE;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTM_NEWROUTE;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTN_UNICAST;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RTPROT_STATIC;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RT_SCOPE_UNIVERSE;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.RT_TABLE_MAIN;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.rtattr;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.rtmsg;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.AF_INET;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.AF_NETLINK;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.SOCK_RAW;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.INSTANTCE;

import com.github.pangolin.routing.server.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.server.tun.adapter.util.NetUtils2;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;

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

    public static void getAll() {
        final int sockfd = LIBC.socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
        try {
            final sockaddr_nl localAddr = new sockaddr_nl();
            localAddr.nl_family = AF_NETLINK;
            // localAddr.nl_groups = RTMGRP_IPV4_ROUTE | RTMGRP_IPV6_ROUTE;
            localAddr.nl_pid = INSTANTCE.getpid();

            if (INSTANTCE.bind(sockfd, localAddr, localAddr.size()) < 0) {
                throwLastErrorException(Native.getLastError());
            }

            int msgSize = 16 + 12; // nlmsghdr(16) + rtmsg(12) + dst rtattr(4) + dst addr(4) + gw rtattr(4) + gw addr(4) + oif rtattr(4) + ifindex(4)

            // 初始化消息内存
            final Memory buffer = new Memory(msgSize);
            int offset = 0;

            final nlmsghdr hl = new nlmsghdr(buffer.share(offset));
            hl.nlmsg_len = msgSize;
            hl.nlmsg_type = RTM_GETROUTE;
            hl.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
            hl.nlmsg_seq = 1;
            hl.nlmsg_pid = LIBC.getpid();
            hl.write();
            offset += hl.size();

            final rtmsg rt = new rtmsg(buffer.share(offset));
            rt.rtm_family = AF_INET;
//            rt.rtm_dst_len = (byte) 0;
//            rt.rtm_table = (byte) RT_TABLE_MAIN;
//            rt.rtm_protocol = RTPROT_STATIC;
//            rt.rtm_scope = RT_SCOPE_UNIVERSE;
//            rt.rtm_type = RTN_UNICAST;
            rt.write();
            offset += rt.size();

            // 填充rtattr（DST 地址）
//            offset += writeSockAddrIn(buffer.share(offset), (short) RTA_DST, dst);


            // 填充rtattr（GATEWAY 地址）
//            offset += writeSockAddrIn(buffer.share(offset), (short) RTA_GATEWAY, gw);

            // 填充rtattr（Interface）
//            offset += writeInt(buffer.share(offset), ifindex);

            final int written = LIBC.send(sockfd, buffer, offset, 0);
            if (written < 0) {
                throwLastErrorException(Native.getLastError());
            }

            System.out.println("RECV");

            int len;
            final Memory buf = new Memory(2096);
            while ((len = LIBC.recv(sockfd, buf, (int) buf.size(), 0)) > 0) {
                /*-
                 * Parse nlmsg.
                 */
                for (int off = 0; off < len; ) {
                    final Pointer ptr = buf.share(off, len - off);
                    final nlmsghdr nlh = new nlmsghdr(ptr);
                    nlh.read();

                    if (nlh.nlmsg_type == NLMSG_ERROR) {
                        return;
                    }
                    if (nlh.nlmsg_type == NLMSG_DONE) {
                        return;
                    }

                    if (nlh.nlmsg_type == RTM_NEWROUTE) {
                        int bytesRead = nlh.size();

                        final rtmsg rtmsg = new rtmsg(ptr.share(bytesRead));
                        rtmsg.read();
                        bytesRead += rtmsg.size();

                        final int rtattr_len = nlh.nlmsg_len - nlh.size() - rtmsg.size();
                        final rtattr[] tab = parseRtattr(ptr.share(bytesRead), rtattr_len);

                        if (null != tab[RTA_DST]) {
                            final rtattr rta = tab[RTA_DST];
                            byte[] data = rta.getPointer().getByteArray(rta.size(), rta.rta_len - rta.size());
                            System.out.printf("DST: %-20s\t", NetUtil.bytesToIpAddress(data));
                            byte[] bytes = NetUtils2.cidrPrefixToNetmask(data, rtmsg.rtm_dst_len);
                            System.out.printf("MASK: %-20s(%s)\t", NetUtil.bytesToIpAddress(bytes), rtmsg.rtm_dst_len);
                        } else {
                            System.out.printf("DST: %-20s\t", "0.0.0.0");
                            System.out.printf("MASK: %-20s\t", "0.0.0.0");
                        }

                        if (null != tab[RTA_GATEWAY]) {
                            final rtattr rta = tab[RTA_GATEWAY];
                            byte[] data = rta.getPointer().getByteArray(rta.size(), rta.rta_len - rta.size());
                            System.out.printf("GW: %-20s\t", NetUtil.bytesToIpAddress(data));
                        } else {
                            System.out.printf("GW: %-20s\t", "0.0.0.0");
                        }

                        if (null != tab[RTA_OIF]) {
                            final rtattr rta = tab[RTA_OIF];
                            int ifindex = rta.getPointer().getInt(rta.size());
                            final byte[] _buf = new byte[IFNAMSIZ];
                            LIBC.if_indextoname(ifindex, _buf);
                            String dev = Native.toString(_buf);
                            System.out.printf("IF: %s\t", dev);
                        }
                        if (null != tab[RTA_PRIORITY]) {
                            final rtattr rta = tab[RTA_PRIORITY];
                            final int metrics = rta.getPointer().getInt(rta.size());
                            System.out.printf("Metrics: %s", metrics);
                        }

                        System.out.println();
                    }
                    off += nlh.nlmsg_len;
                }
            }
        } finally {
            LIBC.close(sockfd);
        }
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

}