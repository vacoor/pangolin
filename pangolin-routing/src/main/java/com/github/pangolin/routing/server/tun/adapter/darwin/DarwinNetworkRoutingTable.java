package com.github.pangolin.routing.server.tun.adapter.darwin;

import com.github.pangolin.routing.server.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import static com.github.pangolin.routing.server.tun.adapter.darwin.DarwinUtils.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.cidrToNetmaskAddress;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Sysctl.*;
import static com.sun.jna.Pointer.NULL;

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

    public static void get0() {
        final rt_msghdr req = new rt_msghdr();
        req.rtm_msglen = (short) req.size();
        req.rtm_version = RTM_VERSION;
        req.rtm_type = RTM_GET;
        req.rtm_addrs = 0xFFFF;
        req.write();

        final int fd = LIBC.socket(AF_ROUTE, SOCK_RAW, AF_UNSPEC);
        if (fd < 0) {
            throwLastErrorException(Native.getLastError());
        }

        try {
            final int writtenBytes = LIBC.write(fd, req.getPointer(), req.size());
            if (writtenBytes != req.size()) {
                throwLastErrorException(Native.getLastError());
            }

            int len;
            final Memory buf = new Memory(4096);
            while ((len = LIBC.read(fd, buf, buf.size())) > 0) {
                final Pointer ptr = buf.share(0, len);
                final rt_msghdr hdr = new rt_msghdr(ptr);
                final int blen = hdr.rtm_msglen - hdr.size();

                System.out.println(hdr.rtm_type);
            }
        } finally {
            LIBC.close(fd);
        }
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
            hdr.rtm_version = RTM_VERSION;
            hdr.rtm_type = rtm_type;
            hdr.rtm_flags = RTF_UP | RTF_GATEWAY | RTF_STATIC;
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

    public static void main(String[] args) {
        final int[] mib = {
                CTL_NET,          // 网络子系统
                AF_ROUTE,         // 路由协议族
                0,                // 所有协议
                0,                // 所有接口
                NET_RT_DUMP,      // 操作类型：导出路由表
                0                 // 保留位
        };

        final IntByReference lenRef = new IntByReference(0);
        if (LIBC.sysctl(mib, mib.length, NULL, lenRef, NULL, new IntByReference(0)) < 0) {
            throwLastErrorException(Native.getLastError());
        }

        final int length = lenRef.getValue();
        if (length <= 0) {
            return;
        }

        final Memory buffer = new Memory(length);
        if (LIBC.sysctl(mib, mib.length, buffer, new IntByReference(length), NULL, new IntByReference(0)) < 0) {
            buffer.close();
            throwLastErrorException(Native.getLastError());
        }

//        System.out.println(length);

        final Pointer ptr = buffer.share(0, length);
        for (int offset = 0; offset < length; ) {
            final rt_msghdr rtm = new rt_msghdr(ptr.share(offset));
            rtm.read();

//            System.out.printf("Route Entry: Type=%s, Flags=[%s,%s], Addrs=%s\n", rtm.rtm_type, rtm.rtm_flags & RTF_UP, rtm.rtm_flags & RTF_GATEWAY, rtm.rtm_addrs);
            final int l = rtm.rtm_msglen - rtm.size();
//            System.out.println("Len = " + l);

            if ((rtm.rtm_addrs & RTA_DST) != 0) {
                printAddr(ptr.share(offset + rtm.size()), "DST");
            }
            if ((rtm.rtm_addrs & RTA_GATEWAY) != 0) {
                printAddr(ptr.share(offset + rtm.size()), "GW");
            }
            if ((rtm.rtm_addrs & RTA_NETMASK) != 0) {
                printAddr(ptr.share(offset + rtm.size()), "NETMASK");
            }
            /*
            if ((rtm.rtm_addrs & RTA_IFP) != 0) {
                sockaddr_dl sdl = new sockaddr_dl(ptr.share(offset + rtm.size()));
                sdl.read();
                System.out.printf("Dev: %s\t", sdl.sdl_index);
            }
            */
            final byte[] buf = new byte[IFNAMSIZ];
            LIBC.if_indextoname(rtm.rtm_index, buf);
            String dev = Native.toString(buf);
            System.out.printf("Dev: %s\t", dev);
//            System.out.println("Metric: " + rtm.rtm_inits);
            System.out.println();
            offset += rtm.rtm_msglen;
        }
    }

    private static void printAddr(final Pointer ptr, final String type) {
        final int len = ptr.getByte(0) & 0xFF;
        final int family = ptr.getByte(1) & 0xFF;
        if (AF_INET == family) {
            sockaddr_in sockaddr_in = new sockaddr_in(ptr.share(0, len));
            sockaddr_in.read();
            System.out.printf("%s: %s\t", type, toInet4Address(sockaddr_in).getHostAddress());
        } else if (AF_INET6 == family) {
            sockaddr_in6 sockaddr_in = new sockaddr_in6(ptr.share(0, len));
            System.out.printf("%s: %s\t", type, toInet6Address(sockaddr_in).getHostAddress());
        } else {
        }
    }

}