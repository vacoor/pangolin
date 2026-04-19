package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * MIB 计数器家族 — 对齐 Linux {@code LINUX_MIB_*} / {@code TCP_MIB_*}
 * (include/uapi/linux/snmp.h)的 v2 子集。
 *
 * <p>仅收录 v2 实际会投递的条目;扩展时在末尾追加新常量,{@link TcpMibStats} 使用
 * ordinal 作为数组下标,枚举顺序稳定后不要插入或重排现有条目。
 *
 * <p>命名保留 Linux 前缀(去掉 {@code LINUX_MIB_} / {@code TCP_MIB_})以便与
 * {@code /proc/net/netstat}、{@code snmp} dump 逐行对照。
 */
public enum TcpMib {
    /** 对应 {@code LINUX_MIB_PAWSESTABREJECTED}:ESTABLISHED 路径上 PAWS 拒绝的段数。 */
    PAWSESTABREJECTED,
    /** 对应 {@code LINUX_MIB_TCPCHALLENGEACK}:Challenge ACK 发送计数(RFC 5961)。 */
    TCPCHALLENGEACK,
    /** 对应 {@code LINUX_MIB_TCPSYNCHALLENGE}:因 SYN 在非 LISTEN 状态触发的 Challenge ACK。 */
    TCPSYNCHALLENGE,
    /** 对应 {@code LINUX_MIB_OUTOFWINDOWICMPS}:越窗段丢弃后触发的定时器回调(quickack) 计数近似。 */
    OUTOFWINDOWICMPS,
    /** 对应 {@code LINUX_MIB_TCPABORTONDATA}:ESTABLISHED 后对端发数据但本端已 shutdown 关闭。 */
    TCPABORTONDATA,
    /** 对应 {@code LINUX_MIB_TCPABORTONTIMEOUT}:用户超时 / linger2 到期强制 abort。 */
    TCPABORTONTIMEOUT,
    /** 对应 {@code LINUX_MIB_TCPABORTONLINGER}:FIN_WAIT_2 linger2 {@code < 0} 触发的 abort。 */
    TCPABORTONLINGER,
    /** 对应 {@code LINUX_MIB_TCPPUREACKS}:收到的纯 ACK 段数。 */
    TCPPUREACKS,
    /** 对应 {@code LINUX_MIB_TCPOFOQUEUE}:进入 OFO 队列的段数。 */
    TCPOFOQUEUE,
    /** 对应 {@code LINUX_MIB_TCPOFODROP}:因 OFO 预算耗尽被丢弃的段数。 */
    TCPOFODROP,
    /** 对应 {@code LINUX_MIB_TCPOFOMERGE}:OFO 队列内合并入现有段的计数。 */
    TCPOFOMERGE,
    /** 对应 {@code LINUX_MIB_TCPPRUNECALLED}:触发 {@code tcp_prune_queue} 的次数。 */
    TCPPRUNECALLED,
    /** 对应 {@code LINUX_MIB_TCPRCVCOLLAPSED}:在 prune 阶段被合并 / 淘汰的 skb 数。 */
    TCPRCVCOLLAPSED,
    /** 对应 {@code LINUX_MIB_TCPKEEPALIVE}:keepalive 探测发送次数。 */
    TCPKEEPALIVE,
    /** 对应 {@code LINUX_MIB_TCPRETRANSSEGS}:重传段数(用户态 {@code Tcp.RetransSegs})。 */
    TCPRETRANSSEGS,
    /** 对应 {@code LINUX_MIB_TCPTIMEWAITCREATED}:进入 TIME_WAIT bucket 的连接数。 */
    TCPTIMEWAITCREATED,
    /** 对应 {@code LINUX_MIB_TCPTIMEWAITKILLED}:TIME_WAIT bucket 被提前 kill 的计数。 */
    TCPTIMEWAITKILLED,
    /** 对应 {@code LINUX_MIB_TCPTIMEWAITRECYCLED}:TW bucket 被复用(TW_SYN)的次数,v2 暂为 0。 */
    TCPTIMEWAITRECYCLED,
    ;
}
