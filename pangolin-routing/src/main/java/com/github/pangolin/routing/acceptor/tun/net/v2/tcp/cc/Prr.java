package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;

/**
 * Proportional Rate Reduction(RFC 6937) — Recovery 期 cwnd 平滑下降算法。
 *
 * <p>替代经典 NewReno "Fast Retransmit cwnd = ssthresh + 3 inflation" 的硬突变,在
 * Recovery 期间根据 {@code prr_delivered} 比例动态调整 cwnd,让发送速率在丢包前后
 * 保持一致(降低 burst,减少二次丢包)。Linux 自 3.2 起默认启用 PRR(取代 RFC 3517
 * 的 RTX threshold-based deflation)。
 *
 * <p>算法核心(对齐 Linux {@code tcp_cwnd_reduction}):
 * <pre>
 *   pipe = packets_in_flight (= packets_out - sacked_out - lost_out + retrans_out)
 *   if pipe > ssthresh:
 *       sndcnt = floor(prr_delivered * ssthresh / prior_cwnd) - prr_out
 *   else:
 *       // Recovery 末尾(SSRB,Slow-Start Reduction Bound):允许"赶上"
 *       sndcnt = min(ssthresh - pipe, prr_delivered - prr_out)
 *   sndcnt = max(sndcnt, lossSegments)   // 至少能重传 LOST 段
 *   cwnd = pipe + sndcnt
 * </pre>
 *
 * <p>状态字段({@code prr_delivered} / {@code prr_out} / {@code priorCwnd})归属
 * {@link Sender},Recovery 入口由 CC.{@code onStateChange} 调
 * {@link #enterRecovery} 重置;Recovery 期每 ACK 由 CC.{@code onAck} 调
 * {@link #onAck} 计算 sndcnt。
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6937">RFC 6937 PRR</a>
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">Linux tcp_cwnd_reduction</a>
 */
public final class Prr {

    private Prr() {}

    /**
     * 进入 Recovery 时调用 — 重置 PRR 状态。{@code priorCwnd} 已由
     * {@link Sender#tcpInitUndo} 快照,本方法仅清零 {@code prrDelivered / prrOut}。
     */
    public static void enterRecovery(TcpSock sock) {
        Sender s = sock.sender();
        s.prrDelivered(0);
        s.prrOut(0);
    }

    /**
     * Recovery 期间每个 ACK 调用,根据 PRR 算法调整 {@code cwnd}。CC 实现在 onAck
     * 内 Recovery 分支调用本方法替代经典 dupack inflation。
     *
     * @param lossSegments 至少要发的段数(典型为 1,允许重传 LOST 段)
     */
    public static void onAck(TcpSock sock, RateSample rs, int lossSegments) {
        Sender s = sock.sender();

        // delivered:本次 ACK 释放的"成功投递"段数(Karn 之后,跳过重传副本)
        // 简化:直接读 RateSample.ackedPackets;若为 0(纯 dupack)则 delivered=0,
        // 但仍可能有 SACK 标 sacked_out → Linux 会把 sacked_out 增量计入 delivered;
        // v2 简化忽略 SACK 增量。
        int delivered = rs.ackedPackets;
        s.prrDelivered(s.prrDelivered() + Math.max(delivered, 0));

        int pipe = sock.packetsInFlight();
        int ssthresh = s.ssthresh();
        int priorCwnd = Math.max(s.priorCwnd(), 1);

        int sndcnt;
        if (pipe > ssthresh) {
            // PRR 主路径:按比例 deflate
            long num = (long) s.prrDelivered() * ssthresh;
            sndcnt = (int) (num / priorCwnd) - s.prrOut();
        } else {
            // SSRB(Slow-Start Reduction Bound):接近 Recovery 末尾,允许追赶 ssthresh
            int limit = Math.max(s.prrDelivered() - s.prrOut(), lossSegments);
            sndcnt = Math.min(limit, ssthresh - pipe);
        }
        // 至少能发 lossSegments 段,保证 LOST 段能被重传
        sndcnt = Math.max(sndcnt, lossSegments);

        int newCwnd = Math.max(pipe + sndcnt, 1);
        s.cwnd(newCwnd);
    }

    /**
     * sender 在 Recovery 状态下每发出一段(原始 / 重传)调一次,递增 {@code prr_out}。
     * 由 {@code TcpOutput.eventNewDataSent} 与 {@code TcpOutput.retransmitSkb} 调用。
     */
    public static void onSegmentSent(TcpSock sock) {
        if (sock.inRecovery()) {
            Sender s = sock.sender();
            s.prrOut(s.prrOut() + 1);
        }
    }
}
