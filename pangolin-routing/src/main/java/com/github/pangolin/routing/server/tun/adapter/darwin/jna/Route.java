package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public interface Route {
    int RTM_VERSION = 5;

    int RTM_ADD = 0x1;

    int RTF_UP = 0x1;            /* route usable */

    int RTF_GATEWAY = 0x2;     /* destination is a gateway */

    int RTA_DST       =  0x1;     /* destination sockaddr present */
    int RTA_GATEWAY   =  0x2;    /* gateway sockaddr present */
    int RTA_NETMASK    = 0x4;    /* netmask sockaddr present */


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
        public int[] rmx_filler = new int[3];  // 保留字段

        public rt_metrics() {
//             setAlignType(Type.ALIGN_APPLE_SILICON);
            setAlignType(ALIGN_NONE);
        }

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

    class rt_msghdr extends Structure {
        public short rtm_msglen;     /* to skip over non-understood messages */
        public char rtm_version;    /* future binary compatibility */
        public char rtm_type;       /* message type */
        public short rtm_index;      /* index for associated ifp */
        public int rtm_flags;      /* flags, incl. kern & message, e.g. DONE */
        public int rtm_addrs;      /* bitmask identifying sockaddrs in msg */
        public int rtm_pid;        /* identify sender */
        public int rtm_seq;        /* for sender to identify action */
        public int rtm_errno;      /* why failed */
        public int rtm_use;        /* from rtentry */
        // macOs 14.x --> long
        public int rtm_inits;    /* which metrics we are initializing */
        public rt_metrics rtm_rmx = new rt_metrics(); /* metrics themselves */

        public rt_msghdr() {
            setAlignType(ALIGN_NONE);
        }

        public rt_msghdr(final Pointer p) {
//            super(p);
            super(p, ALIGN_NONE);
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
