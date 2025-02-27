package com.github.pangolin.routing;

import com.sun.jna.*;
import com.sun.jna.platform.mac.SystemB;
import java.net.InetAddress;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/*
public class MacOSRouteManager {

    // macOS 专用常量 (来自 <net/route.h>)
    private static final int AF_INET = 2;
    private static final int PF_ROUTE = SystemB.PF_ROUTE;
    private static final int RTM_ADD = 0x1;
    private static final int RTF_GATEWAY = 0x2;
    private static final int RTF_UP = 0x1;
    private static final int RTAX_DST = 0;
    private static final int RTAX_GATEWAY = 1;
    private static final int RTAX_NETMASK = 2;

    // JNA 接口定义
    public interface MacRouteLib extends Library {
        MacRouteLib INSTANCE = Native.load("c", MacRouteLib.class);
        int socket(int domain, int type, int protocol);
        int write(int fd, Pointer buf, int count);
        int close(int fd);
    }

    // macOS 路由消息头 (struct rt_msghdr2)
    public static class RtMsgHdr extends Structure {
        public short rtm_msglen;   // 消息总长度
        public byte rtm_version;   // 协议版本
        public byte rtm_type;     // 消息类型 (RTM_ADD)
        public short rtm_index;    // 接口索引
        public int rtm_flags;     // 标志位
        public int rtm_addrs;     // 地址位掩码
        public int rtm_pid;       // 进程ID
        public int rtm_seq;       // 序列号
        public int rtm_errno;     // 错误码
        public int rtm_use;       // 引用计数
        public long rtm_inits;    // 初始化数据

        @Override
        protected List<String> getFieldOrder() {
            return List.of("rtm_msglen", "rtm_version", "rtm_type", "rtm_index",
                    "rtm_flags", "rtm_addrs", "rtm_pid", "rtm_seq", "rtm_errno",
                    "rtm_use", "rtm_inits");
        }
    }

    public static void addRoutes(List<RouteEntry> routes) {
        int fd = MacRouteLib.INSTANCE.socket(PF_ROUTE, SystemB.SOCK_RAW, AF_INET);
        if (fd < 0) throw new RuntimeException("Failed to create route socket");

        try {
            for (RouteEntry route : routes) {
                // 1. 构造消息头
                RtMsgHdr hdr = new RtMsgHdr();
                hdr.rtm_version = 5;      // macOS 13+ 使用版本5
                hdr.rtm_type = RTM_ADD;
                hdr.rtm_flags = RTF_UP | RTF_GATEWAY;
                hdr.rtm_addrs = (1 << RTAX_DST) | (1 << RTAX_GATEWAY) | (1 << RTAX_NETMASK);
                hdr.rtm_msglen = (short) (hdr.size() + 3 * 16); // 头 + 3个sockaddr

                // 2. 构造数据缓冲区
                Memory buffer = new Memory(256);
                buffer.setOrder(ByteOrder.BIG_ENDIAN); // macOS 网络字节序
                hdr.writeToBytes(buffer);

                // 3. 填充地址数据
                int offset = hdr.size();
                writeSockaddr(buffer.share(offset), route.dest);      // 目标地址
                writeSockaddr(buffer.share(offset + 16), route.gw);   // 网关
                writeSockaddr(buffer.share(offset + 32), route.mask); // 子网掩码

                // 4. 发送路由请求
                int ret = MacRouteLib.INSTANCE.write(fd, buffer, hdr.rtm_msglen);
                if (ret < 0) System.err.println("Write failed: " + SystemB.INSTANCE.strerror(Native.getLastError()));
            }
        } finally {
            MacRouteLib.INSTANCE.close(fd);
        }
    }

    // macOS 专用 sockaddr_in 构造
    private static void writeSockaddr(Pointer ptr, String ip) throws Exception {
        ptr.setByte(0, (byte) AF_INET);           // address family
        ptr.setByte(1, (byte) 0);                 // 端口（路由无需）
        byte[] addr = InetAddress.getByName(ip).getAddress();
        ptr.write(4, addr, 0, 4);                 // IPv4地址
        ptr.setInt(8, 0);                         // 填充字段
    }

    // 路由条目封装
    public static class RouteEntry {
        public String dest;  // 目标网络 (e.g. "192.168.1.0")
        public String mask;  // 子网掩码 (e.g. "255.255.255.0")
        public String gw;    // 网关地址 (e.g. "10.0.0.1")

        public RouteEntry(String dest, String mask, String gw) {
            this.dest = dest;
            this.mask = mask;
            this.gw = gw;
        }
    }

    public static void main(String[] args) {
        List<RouteEntry> routes = new ArrayList<>();
        routes.add(new RouteEntry("192.168.1.0", "255.255.255.0", "10.0.0.1"));
        routes.add(new RouteEntry("172.16.0.0", "255.255.0.0", "10.0.0.2"));
        addRoutes(routes);
    }
}
*/