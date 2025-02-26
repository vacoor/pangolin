package com.github.pangolin.routing.server.tun.adapter.linux;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.sockaddr_in;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.RTA_DST;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.RTA_GATEWAY;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.RTA_NETMASK;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.RTF_GATEWAY;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.RTF_UP;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.RTM_ADD;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.RTM_VERSION;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.rt_msghdr;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.AF_INET;

import com.github.pangolin.routing.server.tun.adapter.darwin.jna.If;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.LibC;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.netty.util.NetUtil;

public class RouteTest {
    public static void main(String[] args) {
        rt_msghdr hdr = new rt_msghdr();
        int addr_size = new If.sockaddr_in().size();
        System.out.println(hdr.size() + "(" + hdr.rtm_rmx.size() + ")" + " + " + addr_size * 3);


        final Memory buffer = new Memory(hdr.size() + 3 * addr_size);

        int offset = 0;
        Pointer ptr = buffer.share(offset);

        hdr = new rt_msghdr(ptr);
        hdr.rtm_msglen = (short) (hdr.size() + 3 * addr_size);
        hdr.rtm_version = RTM_VERSION;
//        hdr.rtm_type = RTM_DELETE;
        hdr.rtm_type = RTM_ADD;
        hdr.rtm_flags = RTF_UP | RTF_GATEWAY;
        hdr.rtm_addrs = RTA_DST | RTA_GATEWAY | RTA_NETMASK;
        hdr.rtm_pid = hdr.rtm_seq = 0;
        hdr.write();


        offset += hdr.size();


        offset += writeSockAddr(ptr.share(offset), toBytes("198.18.0.0"));
        offset += writeSockAddr(ptr.share(offset), toBytes("198.18.0.1"));
        offset += writeSockAddr(ptr.share(offset), toBytes("255.255.255.0"));

        System.out.println(offset);
        int PF_ROUTE = 0x11;
        int SOCK_RAW = 0x03;
        int fd = LibC.socket(PF_ROUTE, SOCK_RAW, 0);
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