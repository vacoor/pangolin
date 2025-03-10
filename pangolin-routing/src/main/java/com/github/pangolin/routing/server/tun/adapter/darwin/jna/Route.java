package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/route.h">net/route.h</a>
 */
public interface Route {

    /**
     * route usable.
     */
    int RTF_UP = 0x1;

    /**
     * destination is a gateway.
     */
    int RTF_GATEWAY = 0x2;

    /**
     * host entry (net otherwise).
     */
    int RTF_HOST = 0x4;

    /**
     * generate new routes on use.
     */
    int RTF_CLONING = 0x100;

    /**
     * manually added.
     */
    int RTF_STATIC = 0x800;

    /**
     * Up the ante and ignore older versions.
     */
    int RTM_VERSION = 5;

    /*-
     * Message types.
     */

    /**
     * Add Route.
     */
    int RTM_ADD = 0x1;

    /**
     * Delete Route.
     */
    int RTM_DELETE = 0x2;

    /**
     * Change Metrics or flags.
     */
    int RTM_CHANGE = 0x3;

    /**
     * Report Metrics.
     */
    byte RTM_GET = 0x4;


    /*-
     * Bitmask values for rtm_addrs.
     */

    /**
     * destination sockaddr present.
     */
    int RTA_DST = 0x1;
    /**
     * gateway sockaddr present.
     */
    int RTA_GATEWAY = 0x2;
    /**
     * netmask sockaddr present.
     */
    int RTA_NETMASK = 0x4;
    /**
     * interface name sockaddr present.
     */
    int RTA_IFP = 0x10;

    /**
     * These numbers are used by reliable protocols for determining
     * retransmission behavior and are included in the routing structure.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/route.h">net/route.h</a>
     */
    class rt_metrics extends Structure {
        public int rmx_locks;      // 路由锁状态 (bitmask)
        public int rmx_mtu;        // 路径MTU（字节）
        public int rmx_hopcount;   // 跳数指标
        public int rmx_expire;     // 超时时间（毫秒）
        public int rmx_recvpipe;   // 接收带宽（字节/秒）
        public int rmx_sendpipe;   // 发送带宽（字节/秒）
        public int rmx_ssthresh;   // TCP慢启动阈值
        public int rmx_rtt;        // 平均往返时间（微秒）
        public int rmx_rttvar;     // RTT方差
        public int rmx_pksent;     // 发送数据包计数（2025新增）
        public int rmx_state;
        public final int[] rmx_filler = new int[3];  // 保留字段

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "rmx_locks", "rmx_mtu", "rmx_hopcount",
                    "rmx_expire", "rmx_recvpipe", "rmx_sendpipe",
                    "rmx_ssthresh", "rmx_rtt", "rmx_rttvar",
                    "rmx_pksent", "rmx_state", "rmx_filler"
            );
        }
    }

    /**
     * Structures for routing messages.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/route.h">net/route.h</a>
     */
    class rt_msghdr extends Structure {
        /**
         * to skip over non-understood messages.
         */
        public short rtm_msglen;
        /**
         * future binary compatibility.
         */
        public byte rtm_version;
        /**
         * message type.
         */
        public byte rtm_type;
        /**
         * index for associated ifp.
         */
        public short rtm_index;
        /**
         * flags, incl. kern & message, e.g. DONE.
         */
        public int rtm_flags;
        /**
         * bitmask identifying sockaddrs in msg.
         */
        public int rtm_addrs;
        /**
         * identify sender.
         */
        public int rtm_pid;
        /**
         * for sender to identify action.
         */
        public int rtm_seq;
        /**
         * why failed.
         */
        public int rtm_errno;
        /**
         * from rtentry.
         */
        public int rtm_use;
        /**
         * which metrics we are initializing.
         */
        public int rtm_inits;
        /**
         * metrics themselves.
         */
        public rt_metrics rtm_rmx = new rt_metrics();

        public rt_msghdr() {
        }

        public rt_msghdr(final Pointer p) {
            super(p);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "rtm_msglen", "rtm_version", "rtm_type",
                    "rtm_index", "rtm_flags", "rtm_addrs",
                    "rtm_pid", "rtm_seq", "rtm_errno",
                    "rtm_use", "rtm_inits", "rtm_rmx"
            );
        }
    }
}
