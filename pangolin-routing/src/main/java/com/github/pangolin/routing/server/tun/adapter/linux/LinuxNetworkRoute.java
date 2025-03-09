package com.github.pangolin.routing.server.tun.adapter.linux;

import com.sun.jna.*;

import java.net.Inet4Address;

import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxUtils.throwLastErrorException;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.*;

public class LinuxNetworkRoute {

    public static void add(final String ifname, final Inet4Address addr, final int prefix, final Inet4Address gwAddr, final boolean delete) {
        int ifindex = if_nametoindex(ifname);
        add(ifindex, addr, prefix, gwAddr, delete);
    }

    public static void add(final int ifindex, final Inet4Address addr, final int prefix, final Inet4Address gwAddr, final boolean delete) {
        final byte[] dst = addr.getAddress();
        final byte[] gw = gwAddr.getAddress();
        final int sockfd = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
        try {
            final sockaddr_nl localAddr = new sockaddr_nl();
            localAddr.nl_family = AF_NETLINK;
            localAddr.nl_pid = API_INSTANTCE.getpid();

            if (API_INSTANTCE.bind(sockfd, localAddr, localAddr.size()) < 0) {
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
            /*
            rtattr rta = new rtattr(buffer.share(offset));
            rta.rta_len = (byte) (rta.size() + dst.length);
            rta.rta_type = (short) RTA_DST;
            rta.write();
            offset += rta.size();

            buffer.write(offset, dst, 0, dst.length);
            offset += dst.length;
            */
            offset += writeSockAddrIn(buffer.share(offset), (short) RTA_DST, dst);


            // 填充rtattr（GATEWAY 地址）
            /*
            rta = new rtattr(buffer.share(offset));
            rta.rta_len = (byte) (rta.size() + gw.length);
            rta.rta_type = (short) RTA_GATEWAY;
            rta.write();
            offset += rta.size();

            buffer.write(offset, gw, 0, gw.length);
            offset += gw.length;
            */
            offset += writeSockAddrIn(buffer.share(offset), (short) RTA_GATEWAY, gw);

            // 填充rtattr（Interface）
            /*
            rta = new rtattr(buffer.share(offset));
            rta.rta_len = (byte) (rta.size() + 4);
            rta.rta_type = (short) RTA_OIF;
            rta.write();
            offset += rta.size();

            buffer.setInt(offset, if_nametoindex(ifname));
            offset += 4;
            */
            offset += writeIfindex(buffer.share(offset), ifindex);

            /*
            final sockaddr_nl.ByRef dest_addr = new sockaddr_nl.ByRef();
            dest_addr.nl_family = AF_NETLINK;
            dest_addr.nl_pid = 0;
            dest_addr.nl_groups = 0;

            final IOVec.ByRef ioVec = new IOVec.ByRef();
            ioVec.iov_base = buffer;
            ioVec.iov_len = offset;

            final MsgHdr msg = new MsgHdr();
            msg.msg_name = dest_addr;
            msg.msg_namelen = new NativeLong(dest_addr.size());
            msg.msg_iov = ioVec;
            msg.msg_iovlen = new NativeLong(1);
            msg.msg_control = null;
            msg.msg_controllen = new NativeLong(0);
            msg.msg_flags = 0;
            */

            // final int written = API_INSTANTCE.sendmsg(sockfd, msg, 0);
            final int written = API_INSTANTCE.send(sockfd, hl.getPointer(), offset, 0);
            if (written < 0) {
                throwLastErrorException(Native.getLastError());
            }
        } finally {
            close(sockfd);
        }
    }

    private static int writeSockAddrIn(final Pointer ptr, final short rtaType, final byte[] addr) {
        rtattr rta = new rtattr(ptr);
        rta.rta_len = (byte) (rta.size() + addr.length);
        rta.rta_type = rtaType;
        rta.write();

        ptr.write(rta.size(), addr, 0, addr.length);
        return rta.rta_len;
    }

    private static int writeIfindex(final Pointer ptr, final int ifindex) {
        rtattr rta = new rtattr(ptr);
        rta.rta_len = (byte) (rta.size() + 4);
        rta.rta_type = (short) RTA_OIF;
        rta.write();
//        offset += rta.size();

        ptr.setInt(rta.size(), ifindex);
//        offset += 4;
        return rta.size() + 4;
    }

}