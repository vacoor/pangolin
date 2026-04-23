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
    /** 对应 {@code LINUX_MIB_TCPDSACKRECV}:入站 SACK 首块被识别为 DSACK 的段数(发送端视角)。 */
    TCPDSACKRECV,
    /**
     * 对应 {@code LINUX_MIB_TCPDSACKUNDO}:因 DSACK 触发 {@code tcp_try_undo_dsack}
     * 的次数。当本 undo epoch 内所有重传副本都被 DSACK 抵消
     * ({@code tp->undo_retrans} 经 {@code tcp_check_dsack} 递减至 0)时记账;
     * 与 {@code TCPFULLUNDO} / {@code TCPLOSSUNDO} 互斥 — {@code TcpAck.tcpAck}
     * 按 FULLUNDO → LOSSUNDO → DSACKUNDO 顺序 else-if 命中,对齐 Linux
     * {@code tcp_packet_delayed}(TSECR)优先于 {@code !tp->undo_retrans} 兜底。
     */
    TCPDSACKUNDO,
    /** 对应 {@code LINUX_MIB_TCPFULLUNDO}:{@code tcp_try_undo_recovery} TSECR 命中后完整 undo 的次数。 */
    TCPFULLUNDO,
    /** 对应 {@code LINUX_MIB_TCPLOSSUNDO}:{@code tcp_try_undo_loss} TSECR 命中后 CA_Loss undo 的次数。 */
    TCPLOSSUNDO,
    /** 对应 {@code LINUX_MIB_TCPLIMITEDTRANSMIT}:RFC 3042 Limited Transmit 触发发送的段数。 */
    TCPLIMITEDTRANSMIT,
    /**
     * 对应 {@code LINUX_MIB_TCPSPURIOUSRTOS}:F-RTO(RFC 5682)判定 RTO 为伪触发并
     * 回滚 {@code cwnd/ssthresh} 的次数。当 CA_Loss 期间首个 ACK 的 {@code snd_una}
     * 追平或越过 RTO 瞬间的 {@code snd_nxt} 快照({@code frto_high_mark})时记账;
     * 与 {@code TCPLOSSUNDO}(TSECR 通道)互补 — {@code TcpAck.tcpAck} 按
     * FULLUNDO → LOSSUNDO → SPURIOUSRTOS → DSACKUNDO 顺序 else-if 命中。
     */
    TCPSPURIOUSRTOS,
    /**
     * 对应 {@code LINUX_MIB_TCPACKSKIPPEDSYNRECV}:SYN_RECV 半开连接阶段因 OOW 限流
     * 被跳过的 ACK / SYN-ACK 发送数(对齐 Linux {@code checkReq} 中
     * {@code oowRateLimited(..., LINUX_MIB_TCPACKSKIPPEDSYNRECV, ...)})。
     */
    TCPACKSKIPPEDSYNRECV,
    /**
     * 对应 {@code LINUX_MIB_TSECRREJECTED}:{@code checkReq} 阶段因 {@code rcv_tsecr}
     * 越出 {@code [snt_tsval_first, snt_tsval_last]} 而被拒绝的段数。
     */
    TSECRREJECTED,
    ;
}
