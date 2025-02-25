package com.github.pangolin.routing.server.tun.adapter.linux;

import com.github.pangolin.routing.server.tun.adapter.darwin.jna.If;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.LibC;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.netty.util.NetUtil;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Route.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.AF_INET;

public class RouteTest {
    public static void main(String[] args) {
        rt_msghdr hdr = new rt_msghdr();
        int addr_size = new If.sockaddr_in().size();
        System.out.println(hdr.size() + "(" + hdr.rtm_rmx.size() + ")" + " + " + addr_size * 3);


        final Memory buffer = new Memory(hdr.size() + 3 * addr_size);


        Pointer ptr = buffer.share(0);

        hdr = new rt_msghdr(ptr);
        hdr.rtm_msglen = (short) (hdr.size() + 3 * addr_size);
        hdr.rtm_version = RTM_VERSION;
//        hdr.rtm_type = RTM_DELETE;
        hdr.rtm_type = RTM_ADD;
        hdr.rtm_flags = RTF_UP | RTF_GATEWAY;
        hdr.rtm_addrs = RTA_DST | RTA_GATEWAY | RTA_NETMASK;
        hdr.rtm_pid = hdr.rtm_seq = 0;
        hdr.write();

//        ptr.setPointer(0, hdr.getPointer());


        int offset = hdr.size();
        offset += write0(ptr.share(offset), toBytes("198.18.0.0"));
        offset += write0(ptr.share(offset), toBytes("198.18.0.1"));
        offset += write0(ptr.share(offset), toBytes("255.255.255.0"));

        System.out.println(offset);
        int PF_ROUTE = 0x11;
        int SOCK_RAW = 0x03;
        int fd = LibC.socket(PF_ROUTE, SOCK_RAW, 0);
        if (fd < 0 ) {
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

    private static int write(Pointer ptr, byte[] addr) {
        ptr.setByte(0, (byte) 16);
        ptr.setByte(1, (byte) AF_INET);
        ptr.setShort(2, (byte) 0);
        ptr.write(4, addr, 0, 4);
        ptr.setLong(8, 0);
        return 16;
    }

    private static int write0(Pointer ptr, byte[] addr) {
        If.sockaddr_in sockaddr_in = new If.sockaddr_in(ptr);
        sockaddr_in.sin_len = (byte) sockaddr_in.size();
        sockaddr_in.sin_family = AF_INET;
        sockaddr_in.sin_addr = addr;
        sockaddr_in.write();
        return sockaddr_in.size();
    }
}