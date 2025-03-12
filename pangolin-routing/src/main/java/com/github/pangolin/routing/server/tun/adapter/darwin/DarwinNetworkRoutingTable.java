package com.github.pangolin.routing.server.tun.adapter.darwin;

import com.github.pangolin.routing.server.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import static com.github.pangolin.routing.server.tun.adapter.darwin.DarwinUtils.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.cidrToNetmaskAddress;

public class DarwinNetworkRoutingTable extends NetworkRoutingTable {

    private static final LibC LIBC = LibC.INSTANTCE;

    private static final int RT_MSGHDR_SIZE = new rt_msghdr(new Pointer(0)).size();
    private static final int SOCKADDR_DL_SIZE = new sockaddr_dl(new Pointer(0)).size();
    private static final int SOCKADDR_IN_SIZE = 16;
    private static final int SOCKADDR_IN6_SIZE = new sockaddr_in6().size();

    private static final DarwinNetworkRoutingTable INSTANCE = new DarwinNetworkRoutingTable();

    private DarwinNetworkRoutingTable() {
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

    public static DarwinNetworkRoutingTable get() {
        return INSTANCE;
    }

    public static void add(final InetAddress dst, final int prefix, final InetAddress gw, final String ifname) {
        final InetAddress netmask = cidrToNetmaskAddress(dst, prefix);
        add(dst, netmask, gw, if_nametoindex0(ifname));
    }

    public static void add(final InetAddress dst, final InetAddress netmask, final InetAddress gw, final String ifname) {
        add(dst, netmask, gw, if_nametoindex0(ifname));
    }

    public static void add(final InetAddress dst, final InetAddress netmask, final InetAddress gw, final int ifindex) {
        route((byte) RTM_ADD, (short) ifindex, dst, gw, netmask);
    }

    public static void delete(final InetAddress dst, final int prefix, final InetAddress gw, final String ifname) {
        final InetAddress netmask = cidrToNetmaskAddress(dst, prefix);
        delete(dst, netmask, gw, if_nametoindex0(ifname));
    }

    public static void delete(final InetAddress dst, final InetAddress netmask, final InetAddress gw, final String ifname) {
        delete(dst, netmask, gw, if_nametoindex0(ifname));
    }


    public static void delete(final InetAddress dst, final InetAddress netmask, final InetAddress gw, final int ifindex) {
        route((byte) RTM_DELETE, (short) ifindex, dst, gw, netmask);
    }

    private static void route(final byte rtm_type, final short rtm_index,
                              final InetAddress dst, final InetAddress gw,
                              final InetAddress netmask) {
        final int sockAddrSize = dst instanceof Inet6Address ? SOCKADDR_IN6_SIZE : SOCKADDR_IN_SIZE;
        final int size = RT_MSGHDR_SIZE + sockAddrSize * 3 + (rtm_index == 0 ? 0 : SOCKADDR_DL_SIZE);
        final Memory buffer = new Memory(size * 1);
        int offset = 0;
        final rt_msghdr hdr = new rt_msghdr(buffer.share(offset));
        for (int i = 0; i < 1; i++) {
            hdr.rtm_msglen = (short) buffer.size();

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

            offset += writeSockAddrIn(buffer.share(offset), dst);
            offset += writeSockAddrIn(buffer.share(offset), gw);
            offset += writeSockAddrIn(buffer.share(offset), netmask);

            if (rtm_index != 0) {
                // 只设置 rtm_index 不设置 RTA_IFP 和这个结构体的话不能保证绑定到网卡.
                offset += writeSockAddrDl(buffer.share(offset), rtm_index);
            }
        }


        final int fd = LIBC.socket(AF_ROUTE, SOCK_RAW, AF_UNSPEC);
        if (fd < 0) {
            throwLastErrorException(Native.getLastError());
        }

        try {
            final int writtenBytes = LIBC.write(fd, buffer, offset);
            if (writtenBytes != offset) {
                throwLastErrorException(Native.getLastError());
            }
        } finally {
            LIBC.close(fd);
        }
    }

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

    private static int writeSockAddrDl(final Pointer ptr, final int ifindex) {
        final sockaddr_dl dl = new sockaddr_dl(ptr);
        dl.sdl_len = (byte) dl.size();
        dl.sdl_family = AF_LINK;
        dl.sdl_index = (short) ifindex;
        dl.write();
        return dl.size();
    }

    private static int if_nametoindex0(final String ifname) {
        final int index = LIBC.if_nametoindex(ifname);
        if (0 == index) {
            throwLastErrorException(Native.getLastError());
        }
        return index;
    }

}