package com.github.pangolin.routing.server.tun.adapter.darwin;

import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.net.Inet4Address;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.sockaddr_dl;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.sockaddr_in;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.if_nametoindex;

public class DarwinNetworkRoute {
    private static final int RT_MSGHDR_SIZE = new rt_msghdr(new Pointer(0)).size();
    private static final int SOCKADDR_IN_SIZE = 16;
    private static final int SOCKADDR_DL_SIZE = new sockaddr_dl(new Pointer(0)).size();


    public static void add(final Inet4Address dst, final Inet4Address netmask, final Inet4Address gw, final String ifname) {
        add(dst, netmask, gw, nameToIndex(ifname));
    }

    private static int nameToIndex(final String ifname) {
        final int index = if_nametoindex(ifname);
        if (0 == index) {
            final int errno = Native.getLastError();
            // final String errmsg = strerror(errno);
            throw new LastErrorException(errno);
        }
        return index;
    }

    public static void add(final Inet4Address dst, final Inet4Address netmask, final Inet4Address gw, final int ifindex) {
        route((byte) RTM_ADD, (short) ifindex, dst, gw, netmask);
    }


    public static void deleteRoute(String ifname,
                                   final Inet4Address dst, final Inet4Address gw,
                                   final Inet4Address netmask) {
        short rtm_index = (short) LibC.if_nametoindex(ifname);
        route((byte) RTM_DELETE, rtm_index, dst, gw, netmask);
    }

    private static void route(final byte rtm_type, final short rtm_index,
                              final Inet4Address dst, final Inet4Address gw,
                              final Inet4Address netmask) {
        final int size = RT_MSGHDR_SIZE + SOCKADDR_IN_SIZE * 3 + (rtm_index == 0 ? 0 : SOCKADDR_DL_SIZE);
        final Memory buffer = new Memory(size * 1);

        int offset = 0;
        final rt_msghdr hdr = new rt_msghdr(buffer.share(offset));
        for (int i = 0; i < 1; i++) {
            hdr.rtm_msglen = (short) size;

            hdr.rtm_type = rtm_type;
            hdr.rtm_flags = RTF_UP | RTF_GATEWAY | RTF_STATIC;
            hdr.rtm_version = RTM_VERSION;
            hdr.rtm_addrs = RTA_DST | RTA_GATEWAY | RTA_NETMASK;
            if (rtm_index != 0) {
                hdr.rtm_index = rtm_index;
                hdr.rtm_addrs |= RTA_IFP;
            }
            hdr.rtm_pid = 0;
            hdr.rtm_seq = i + 1;

            hdr.write();

            offset += hdr.size();
            offset += writeSockAddrIn(buffer.share(offset), dst.getAddress());
            offset += writeSockAddrIn(buffer.share(offset), gw.getAddress());
            offset += writeSockAddrIn(buffer.share(offset), netmask.getAddress());

            if (rtm_index != 0) {
                // 只设置 rtm_index 不设置 RTA_IFP 和这个结构体的话不能保证绑定到网卡.
                offset += writeSockAddrDl(buffer.share(offset), rtm_index);
            }
        }


        final int fd = LibC.socket(AF_ROUTE, SOCK_RAW, AF_INET);
        if (fd <= 0) {
            throw new LastErrorException(fd);
        }

        final int writtenBytes = LibC.write(fd, buffer, offset);
        if (writtenBytes != offset) {
            throw new IllegalStateException("部分成功");
        }
        LibC.close(fd);
    }

    private static int writeSockAddrIn(final Pointer ptr, final byte[] addr) {
        final sockaddr_in in = new sockaddr_in(ptr);
        final int size = in.size();
        in.sin_len = (byte) size;
        in.sin_family = AF_INET;
        in.sin_addr = addr;
        in.write();
        return size;
    }

    private static int writeSockAddrDl(final Pointer ptr, final int ifindwx) {
        final sockaddr_dl dl = new sockaddr_dl(ptr);
        dl.sdl_len = (byte) dl.size();
        dl.sdl_family = AF_LINK;
        dl.sdl_index = (short) ifindwx;
        dl.write();
        return dl.size();
    }

}