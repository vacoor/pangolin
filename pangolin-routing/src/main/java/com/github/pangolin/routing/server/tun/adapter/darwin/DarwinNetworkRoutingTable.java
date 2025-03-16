package com.github.pangolin.routing.server.tun.adapter.darwin;

import com.github.pangolin.routing.server.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.server.tun.adapter.util.NetUtils2;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

import static com.github.pangolin.routing.server.tun.adapter.darwin.DarwinUtils.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Sysctl.CTL_NET;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.cidrToNetmaskAddress;
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

    private static void route(final byte rtm_type, final short rtm_index,
                              final InetAddress dst, final InetAddress gw,
                              final InetAddress netmask) {
        final int sockAddrSize = dst instanceof Inet6Address ? SOCKADDR_IN6_SIZE : SOCKADDR_IN_SIZE;
        final int size = RT_MSGHDR_SIZE + sockAddrSize * 3 + (rtm_index == 0 ? 0 : SOCKADDR_DL_SIZE);
        final Memory buffer = new Memory(size * 1);
        int offset = 0;
        for (int i = 0; i < 1; i++) {
            final rt_msghdr hdr = new rt_msghdr(buffer.share(offset));
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

    static void getAll() {
        /*-
         * net sub-system, route address family, unspec protocol, all interface, dump route table, reserved
         */
        final IntByReference sizeRef = new IntByReference(0);
        final int[] mib = {CTL_NET, AF_ROUTE, 0, AF_UNSPEC, NET_RT_DUMP, 0};
        if (LIBC.sysctl(mib, mib.length, NULL, sizeRef, NULL, new IntByReference(0)) < 0) {
            throwLastErrorException(Native.getLastError());
        }

        final int length = sizeRef.getValue();
        if (length <= 0) {
            return;
        }

        final Memory buffer = new Memory(sizeRef.getValue());
        if (LIBC.sysctl(mib, mib.length, buffer, sizeRef, NULL, new IntByReference(0)) < 0) {
            buffer.close();
            throwLastErrorException(Native.getLastError());
        }

        final int size = sizeRef.getValue();
        final Pointer ptr = buffer.share(0, size);
        for (int offset = 0; offset < size; ) {
            final rt_msghdr rtm = new rt_msghdr(ptr.share(offset));
            rtm.read();

            process(rtm);

            offset += rtm.rtm_msglen;
        }
    }

    private static int sa_size(final int len) {
        return len >= 16 ? len : 16;
    }

    public static void get0() {
        final Memory memory = new Memory(1024);
        final rt_msghdr req = new rt_msghdr(memory);
        req.rtm_msglen = (short) (req.size() + 16);
        req.rtm_version = RTM_VERSION;
        req.rtm_type = RTM_GET;
        req.rtm_addrs = RTA_DST | RTA_GATEWAY | RTA_NETMASK;
//        req.rtm_addrs = RTA_DST;
//        req.rtm_flags = RTF_UP;
//        req.rtm_index = 4;
        req.rtm_seq = 1;
        req.rtm_pid = LIBC.getpid();
        req.write();

        sockaddr_in sin = new sockaddr_in(memory.share(req.size()));
        sin.sin_len = (byte) sin.size();
//        sin.sin_family = AF_INET | AF_INET6;
        sin.sin_family = AF_INET;
//        writeSockAddr4(sin, new byte[]{0, 0, 0, 0});
        sin.write();

        final int fd = LIBC.socket(AF_ROUTE, SOCK_RAW, AF_UNSPEC);
        if (fd < 0) {
            throwLastErrorException(Native.getLastError());
        }

        try {
            final int writtenBytes = LIBC.write(fd, memory, req.size() + sin.size());
            if (writtenBytes != req.size() + sin.size()) {
                throwLastErrorException(Native.getLastError());
            }
            /*
            if (writtenBytes != req.size()) {
                throwLastErrorException(Native.getLastError());
            }
            */

            int len;
            final Memory buf = new Memory(4096);
            while ((len = LIBC.read(fd, buf, buf.size())) > 0) {
                final Pointer ptr = buf.share(0, len);
                final rt_msghdr hdr = new rt_msghdr(ptr);
                hdr.read();
                process(hdr);
            }
        } finally {
            LIBC.close(fd);
        }
    }

    private static void process(final rt_msghdr rtm) {
        final Pointer ptr = rtm.getPointer();
        int bytesRead = rtm.size();
        final int end = rtm.rtm_msglen;

        if ((rtm.rtm_flags & RTF_UP) == 0
//                    || (rtm.rtm_flags & RTF_STATIC) == 0
                || rtm.rtm_type != RTM_GET
//                || (rtm.rtm_flags & RTF_CLONING) != 0
//                || ((rtm.rtm_flags & (RTF_STATIC)) == 0)
        ) {
            return;
        }

        final Pointer[] tab = new Pointer[RTAX_MAX];
        for (int i = 0; i < RTAX_MAX && bytesRead < end && bytesRead + 16 <= end; i++) {
            if ((rtm.rtm_addrs & (1 << i)) != 0) {
//                    final int len = align(ptr.getByte(bytesRead) & 0xFF, 4);
                int len = align(ptr.getByte(bytesRead) & 0xFF, 4);
                if (len > 0) {
                    final int family = ptr.getByte(bytesRead + 1) & 0xFF;
                    tab[i] = ptr.share(bytesRead);
                } else {
                    // skip 4-bytes pad.
                    len = 4;
                }

                // move pointer to next sockaddr.
//                    bytesRead += len;
//                    bytesRead += sa_size(len);
//                    bytesRead += align(len, 4);
//                    bytesRead += roundup(len, 4);
//                    bytesRead += roundup(len, 4);
                bytesRead += roundup(len, 4);
            }
        }

        if ((tab[RTAX_DST]) != null) {
            printAddr(tab[RTAX_DST], "DST");
        }
        if (tab[RTAX_GATEWAY] != null) {
            printAddr(tab[RTAX_GATEWAY], "GW");
        }
        if (tab[RTAX_NETMASK] != null) {
            printAddr(tab[RTAX_NETMASK], "GENMASK");
//            } else if ((rtm.rtm_flags & RTF_HOST) != 0) {
//                System.out.printf("GENMASK: 255.255.255.255\t");
//            } else if ((rtm.rtm_flags & RTF_HOST) == 0) {
        }

        if (tab[RTAX_IFP] != null) {
            sockaddr_dl d = new sockaddr_dl(tab[RTAX_IFP]);
            d.read();
            System.out.printf("Via %s\t", d.sdl_index);
        }

        String dev = if_indextoname(rtm.rtm_index);
        System.out.printf("Dev: %s\t", dev);
        System.out.println();

        // move pointer to next route entry.
        // offset += roundup(rtm.rtm_msglen, 4);
//            offset += rtm.rtm_msglen;
    }

    private static int roundup(final int len, final int align) {
        return ((len) > 0 ? (1 + (((len) - 1) | (align - 1))) : align);
    }

    private static int roundup2(int a) {
        return ((a) > 0 ? (1 + (((a) - 1) | (8-1))) :8);
    }

    private static int roundup32(final int n, final int align) {
        return (((n) + align - 1) & ~(align - 1));
    }

    private static int align(final int len, final int align) {
        return (len + align - 1) & ~(align - 1);
    }

    private static String if_indextoname(final int ifindex) {
        final byte[] buf = new byte[IFNAMSIZ];
        LIBC.if_indextoname(ifindex, buf);
        return Native.toString(buf);
    }

    public static void main(String[] args) {
        getAll();
        /*
        System.out.println(networkCidr(new byte[]{
                (byte) 192,
                (byte) 168,
                1,
                10
        }));
        */
    }

    private static int printAddr(final Pointer ptr, final String type) {
        final int len = ptr.getByte(0) & 0xFF;
        if (len > 0) {
                final int family = ptr.getByte(1) & 0xFF;
                if (AF_INET == family) {
                    sockaddr_in sockaddr_in = new sockaddr_in(ptr.share(0, len));
                    sockaddr_in.read();

                    System.out.printf("%s: %s\t", type, toInet4Address(sockaddr_in).getHostAddress());
                } else if (AF_INET6 == family) {
                    sockaddr_in6 sockaddr_in = new sockaddr_in6(ptr.share(0, len));
                    System.out.printf("%s: %s\t", type, toInet6Address(sockaddr_in).getHostAddress());
                } else if (AF_LINK == family) {
                    sockaddr_dl sd = new sockaddr_dl(ptr.share(0, len));
                    sd.read();
                    System.out.printf("%s: if_index=%s\t", type, sd.sdl_index);
                } else if (len <= 8 && family == 0xFF) {
                    /*-
                     * len -> non-zero bytes.
                     * family -> -1
                     * -1 (port)
                     * -1 (port)
                     * netmask 4-bytes
                     */
                    assert -1 == ptr.getByte(2);
                    assert -1 == ptr.getByte(3);
                    /*-
                     *
                     */
//                    byte[] byteArray = ptr.getByteArray(4, len - 4);
                    byte[] byteArray = ptr.getByteArray(4, 4);
                    byte[] bytes = Arrays.copyOf(byteArray, 4);
                    System.out.printf("%s: %s\t", type, NetUtil.bytesToIpAddress(bytes));
                } else if (len > 8 && len <= 16 && family == 0xFF) {
                    /*-
                     * len -> non-zero bytes.
                     * family -> -1
                     * -1 (port)
                     * -1 (port)
                     * -1 (flowinfo)
                     * -1 (flowinfo)
                     * -1 (flowinfo)
                     * -1 (flowinfo)
                     * netmask n-bytes
                     */
                    /*-
                     * port
                     */
                    assert -1 == ptr.getByte(2);
                    assert -1 == ptr.getByte(3);
                    /*-
                     * flowinfo
                     */
                    assert -1 == ptr.getByte(4);
                    assert -1 == ptr.getByte(5);
                    assert -1 == ptr.getByte(6);
                    assert -1 == ptr.getByte(7);

                    byte[] byteArray = ptr.getByteArray(8, len - 8);
                    byte[] bytes = Arrays.copyOf(byteArray, 16);
                    int i = NetUtils2.netmaskToPrefixLength(bytes);
                    System.out.printf("%s: /%s\t", type, i);
                } else {
                    // 12
                    System.out.printf("Family: " + family);
//                    byte[] netmask = ptr.getByteArray(1, 4);
//                    System.out.printf("%s: %s\t", type, NetUtil.bytesToIpAddress(netmask));
                }
        } else {
            System.out.printf("");
        }
        return len;
    }

    private static int networkCidr(final byte[] network) {
        int prefix = 0;
        for (int i = 0; i < network.length; i++) {
            for (int j = 0; j < Byte.SIZE; j++) {
                if ((network[i] & (1 << (Byte.SIZE - j - 1))) != 0) {
                    prefix = i * Byte.SIZE + j + 1;
                }
            }
        }
        return prefix;
    }

}