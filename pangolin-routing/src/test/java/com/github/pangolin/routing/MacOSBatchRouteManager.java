package com.github.pangolin.routing;

import com.sun.jna.*;
import com.sun.jna.platform.mac.SystemB;
import java.net.InetAddress;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/*
public class MacOSBatchRouteManager {

    // macOS 专用常量
    private static final int AF_INET = 2;
    private static final int PF_ROUTE = SystemB.PF_ROUTE;
    private static final int RTM_ADD = 0x1;
    private static final int RTF_GATEWAY = 0x2;
    private static final int RTF_UP = 0x1;

    // JNA 接口
    public interface MacRouteLib extends Library {
        MacRouteLib INSTANCE = Native.load("c", MacRouteLib.class);
        int socket(int domain, int type, int protocol);
        int write(int fd, Pointer buf, int count);
        int close(int fd);
    }

    // 路由消息头 (struct rt_msghdr2)
    public static class RtMsgHdr extends Structure {
        public short rtm_msglen;   // 消息总长度
        public byte rtm_version = 5; // macOS 15+ 要求版本5
        public byte rtm_type;      // 消息类型
        public short rtm_index;    // 接口索引
        public int rtm_flags;      // 标志位
        public int rtm_addrs;      // 地址掩码
        // ... 其他字段省略（完整实现需包含所有字段）

        @Override
        protected List<String> getFieldOrder() {
            return List.of("rtm_msglen", "rtm_version", "rtm_type", 
                          "rtm_index", "rtm_flags", "rtm_addrs");
        }
    }

    // 批量添加方法（核心优化）
    public static void batchAddRoutes(List<RouteEntry> routes) {
        int fd = MacRouteLib.INSTANCE.socket(PF_ROUTE, SystemB.SOCK_RAW, AF_INET);
        if (fd < 0) throw new RuntimeException("Socket error: " + SystemB.INSTANCE.strerror(Native.getLastError()));

        try {
            // 1. 预计算总缓冲区大小
            int totalSize = routes.stream()
                .mapToInt(route -> 64) // 每条消息约64字节（含3个sockaddr）
                .sum();

            Memory buffer = new Memory(totalSize);
            buffer.setOrder(ByteOrder.BIG_ENDIAN); // 关键！macOS 强制大端序
            Pointer ptr = buffer;

            // 2. 批量构造消息
            for (RouteEntry route : routes) {
                // 构造单个消息头
                RtMsgHdr hdr = new RtMsgHdr();
                hdr.rtm_type = RTM_ADD;
                hdr.rtm_flags = RTF_UP | RTF_GATEWAY;
                hdr.rtm_addrs = 0x7; // RTA_DST | RTA_GATEWAY | RTA_NETMASK
                hdr.rtm_msglen = (short) (hdr.size() + 3 * 16); // 头 + 3*16字节地址数据

                // 写入消息头
                hdr.writeTo(ptr);
                ptr = ptr.share(hdr.size());

                // 填充地址数据
                writeSockaddr(ptr, route.dest);
                writeSockaddr(ptr.share(16), route.gw);
                writeSockaddr(ptr.share(32), route.mask);
                ptr = ptr.share(48); // 跳转到下条消息起始位置
            }

            // 3. 单次系统调用写入所有路由
            int ret = MacRouteLib.INSTANCE.write(fd, buffer, totalSize);
            if (ret < totalSize) handlePartialErrors(ret, buffer, routes.size());

        } finally {
            MacRouteLib.INSTANCE.close(fd);
        }
    }

    // 处理部分写入错误（2025 年增强）
    private static void handlePartialErrors(int bytesWritten, Memory buffer, int routeCount) {
        int msgSize = 64; // 每条消息固定64字节
        int successCount = bytesWritten / msgSize;
        
        System.err.printf("部分成功: %d/%d 条路由已添加\n", successCount, routeCount);
        for (int i = successCount; i < routeCount; i++) {
            int offset = i * msgSize;
            RtMsgHdr hdr = new RtMsgHdr();
            hdr.getPointer().write(0, buffer.getByteArray(offset, hdr.size()), 0, hdr.size());
            System.err.printf("失败路由: type=0x%x flags=0x%x\n", hdr.rtm_type, hdr.rtm_flags);
        }
    }

    // 其他工具方法与 RouteEntry 类同前文
}
*/
