package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.github.pangolin.routing.server.tun.adapter.linux.jna.LibC;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.sockaddr_in;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;

public class RouteTest {
    private static final int RT_MSGHDR_SIZE = new rt_msghdr(new Pointer(0)).size();
    private static final int SOCKADDR_SIZE = 16;


    public static void addRoute(String ifname,
                                final Inet4Address dst, final Inet4Address gw,
                                final Inet4Address netmask) {
        short rtm_index = (short) LibC.if_nametoindex(ifname);
        System.out.println("INDEX = " + rtm_index);
        route((byte) RTM_ADD, rtm_index, dst, gw, netmask);
    }

    public static void deleteRoute(String ifname,
                                final Inet4Address dst, final Inet4Address gw,
                                final Inet4Address netmask) {
        short rtm_index = (short) LibC.if_nametoindex(ifname);
        System.out.println("INDEX = " + rtm_index);
        route((byte) RTM_DELETE, rtm_index, dst, gw, netmask);
    }

    private static void route(final byte rtm_type, final short rtm_index,
                              final Inet4Address dst, final Inet4Address gw,
                              final Inet4Address netmask) {
        final int size = RT_MSGHDR_SIZE + SOCKADDR_SIZE * 3;
        final Memory buffer = new Memory(size * 1);

        int offset = 0;
        for (int i = 0; i < 1; i++) {
            final rt_msghdr hdr = new rt_msghdr(buffer.share(offset));
            hdr.rtm_msglen = (short) size;
            hdr.rtm_version = RTM_VERSION;

//        hdr.rtm_type = RTM_DELETE;
            hdr.rtm_type = rtm_type;
            hdr.rtm_flags = RTF_UP | RTF_GATEWAY | RTF_STATIC;
            hdr.rtm_addrs = RTA_DST | RTA_GATEWAY | RTA_NETMASK;
            hdr.rtm_index = rtm_index;
            hdr.rtm_pid = 0;
            hdr.rtm_seq = i;
            hdr.write();

            offset += hdr.size();
            offset += writeSockAddr(buffer.share(offset), dst.getAddress());
            offset += writeSockAddr(buffer.share(offset), gw.getAddress());
            offset += writeSockAddr(buffer.share(offset), netmask.getAddress());
        }


        int fd = LibC.socket(AF_ROUTE, SOCK_RAW, 0);
        if (fd < 0) {
            System.out.println("FD: " + fd);
            return;
        }
        int writtenBytes = LibC.write(fd, buffer, offset);

        System.out.println("WRITE: " + writtenBytes);
        LibC.close(fd);
    }

    private static byte[] toBytes(String ip) {
        return NetUtil.createByteArrayFromIpAddressString(ip);
    }

    private static int writeSockAddr(final Pointer ptr, final byte[] addr) {
        final sockaddr_in in = new sockaddr_in(ptr);
        final int size = in.size();
        in.sin_len = (byte) size;
        in.sin_family = AF_INET;
        in.sin_addr = addr;
        in.write();
        return size;
    }

}