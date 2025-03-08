package com.github.pangolin.routing.server.tun.adapter.linux.jna;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public interface Netlink {
    int NETLINK_ROUTE = 0;

    int NLM_F_REQUEST = 0x01;    /* It is request message. 	*/
    int NLM_F_MULTI = 0x02;    /* Multipart message, terminated by NLMSG_DONE */
    int NLM_F_ACK = 0x04;    /* Reply with ack, with zero or error code */
    int NLM_F_ECHO = 0x08;    /* Receive resulting notifications */
    int NLM_F_DUMP_INTR = 0x10;    /* Dump was inconsistent due to sequence change */
    int NLM_F_DUMP_FILTERED = 0x20;    /* Dump was filtered as requested */

    /* Modifiers to NEW request */
    int NLM_F_REPLACE = 0x100;    /* Override existing		*/
    int NLM_F_EXCL = 0x200;    /* Do not touch, if it exists	*/
    int NLM_F_CREATE = 0x400;    /* Create, if it does not exist	*/
    int NLM_F_APPEND = 0x800;    /* Add to end of list		*/

    /**
     * https://github.com/torvalds/linux/blob/master/include/uapi/linux/netlink.h#L37
     */
    // 定义Netlink地址结构体
    @Structure.FieldOrder({"nl_family", "nl_pad", "nl_pid", "nl_groups"})
    class sockaddr_nl extends Structure {
        public short nl_family;  // AF_NETLINK=16
        public short nl_pad;     // 填充字段
        public int nl_pid;       // 进程PID（用户态设为0）
        public int nl_groups;    // 多播组掩码

        public static class ByRef extends sockaddr_nl implements ByReference {
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/netlink.h#L52">nlmsghdr</a>
     */
    @Structure.FieldOrder({"nlmsg_len", "nlmsg_type", "nlmsg_flags", "nlmsg_seq", "nlmsg_pid"})
    class nlmsghdr extends Structure {
        public int nlmsg_len;    // 消息总长度
        public short nlmsg_type; // RTM_NEWADDR=20（新增地址）
        public short nlmsg_flags;// NLM_F_REQUEST | NLM_F_CREATE
        public int nlmsg_seq;    // 序列号
        public int nlmsg_pid;    // 发送方PID

        public nlmsghdr() {
            super(ALIGN_NONE);
        }

        public nlmsghdr(final Pointer p) {
            super(p, ALIGN_NONE);
        }
    }



    // 地址信息结构
    @Structure.FieldOrder({"ifa_family", "ifa_prefixlen", "ifa_flags", "ifa_scope", "ifa_index"})
    class ifaddrmsg extends Structure {
        public byte ifa_family;    // AF_INET=2
        public byte ifa_prefixlen;// 子网掩码位数（如24）
        public byte ifa_flags;     // 标志位（通常为0）
        public byte ifa_scope;     // 作用域（如RT_SCOPE_UNIVERSE）
        public int ifa_index;      // 网卡索引（通过if_nametoindex获取）

        public ifaddrmsg() {
        }

        public ifaddrmsg(final Pointer p) {
            super(p);
        }
    }


    // 映射 struct msghdr
    public class MsgHdr extends Structure {
        public sockaddr_nl.ByRef msg_name;     // 地址结构体指针（如 sockaddr_nl）‌:ml-citation{ref="4,7" data="citationList"}
        public NativeLong msg_namelen;      // 地址长度
        public IOVec.ByRef msg_iov;      // 指向 iovec 数组的指针 ‌:ml-citation{ref="4,7" data="citationList"}
        public NativeLong msg_iovlen;       // iovec 数组长度
        public Pointer msg_control;  // 辅助数据（通常为 NULL）
        public NativeLong msg_controllen;   // 辅助数据长度
        public int msg_flags;        // 标志位（通常忽略）

        public MsgHdr() {
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("msg_name", "msg_namelen", "msg_iov", "msg_iovlen",
                    "msg_control", "msg_controllen", "msg_flags");
        }

        public static class ByRef extends MsgHdr implements ByReference {
        }
    }

    // 映射 struct iovec
    public class IOVec extends Structure {
        public Pointer iov_base;  // 数据缓冲区起始地址 ‌:ml-citation{ref="4,7" data="citationList"}
        public int iov_len;       // 缓冲区长度

        public IOVec() {
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("iov_base", "iov_len");
        }

        public static class ByRef extends IOVec implements ByReference {
        }
    }



}
