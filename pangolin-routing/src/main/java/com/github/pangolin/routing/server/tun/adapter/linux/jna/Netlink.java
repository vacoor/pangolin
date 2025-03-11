package com.github.pangolin.routing.server.tun.adapter.linux.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

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

}
