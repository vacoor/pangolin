package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * 丢包检测与恢复 SPI(骨架)。
 *
 * <p>对齐 Linux 内核 {@code tcp_input.c} / {@code tcp_recovery.c} 中的
 * {@code tcp_ca_event} + {@code tcp_mark_skb_lost} + {@code tcp_rack_mark_lost}
 * 路径 — 把"检测哪些段丢了、什么时候进入/退出 Recovery、是否 undo"从 {@code core}
 * 主路径解耦,允许 {@code ext/recovery/<algo>} 下挂 RACK-TLP / SACK-based /
 * NewReno DupACK 等不同算法。
 *
 * <p>本接口仅定义契约,<b>本提交不接通主路径</b> — 现有 NewReno + RACK 恢复逻辑仍
 * 内嵌在 {@link TcpRetransmitter} / {@link TcpAck} / {@link TcpSock} 中;
 * {@link ClassicRecovery} 是 delegate 目标的占位,本提交不被调用。切换与迁移由
 * 后续专项 phase 完成(见 {@code tcp.java.packages.md} §7 末段)。
 *
 * <p>语义分工:
 * <ul>
 *   <li>进入/退出 Recovery 的窗口控制 → {@link CongestionControl}(ssthresh / cwnd);</li>
 *   <li>决定哪些段已"丢" → 本接口;</li>
 *   <li>RTT 样本 → {@link RttEstimator}。</li>
 * </ul>
 */
public interface LossRecovery {

    /**
     * ACK 到达时的主钩子 — 由 {@link TcpAck} 在累计 ACK / SACK ingest 之后调用。
     *
     * <p>对齐 Linux {@code tcp_fastretrans_alert}(tcp_input.c):根据 dupacks /
     * SACK 块 / DSACK / TSECR 综合判断是否需要进入 RECOVERY、标记 SKB 丢失、触发
     * 快速重传。
     *
     * @param sk  套接字
     * @param ack 本次 ACK 的元信息(序号 / SACK blocks / TSECR 等)
     */
    default void onAck(TcpSock sk, AckContext ack) {
        throw new UnsupportedOperationException("LossRecovery.onAck not wired yet");
    }

    /**
     * RTO 计时器触发时的回调。
     *
     * <p>对齐 Linux {@code tcp_retransmit_timer} + {@code tcp_enter_loss}:
     * 进入 CA_Loss、所有 out-of-SACK 段标记丢失、重传最老段。
     *
     * @param sk 套接字
     */
    default void onRto(TcpSock sk) {
        throw new UnsupportedOperationException("LossRecovery.onRto not wired yet");
    }

    /**
     * 判定某段是否应立即重传。
     *
     * <p>对齐 Linux {@code tcp_rack_mark_lost} 的"被 RACK 时间窗判定丢失"或
     * {@code tcp_mark_head_lost} 的"累计 SACK reordering 越界"。
     *
     * @param sk  套接字
     * @param skb 待判定的重传队列段
     * @return {@code true} 表示应立即重传
     */
    default boolean shouldRetransmit(TcpSock sk, TcpSegment skb) {
        throw new UnsupportedOperationException("LossRecovery.shouldRetransmit not wired yet");
    }

    /**
     * 伪丢失 undo 钩子 — DSACK / TSECR / F-RTO 触发。
     *
     * <p>对齐 Linux {@code tcp_try_undo_recovery} / {@code tcp_try_undo_loss} /
     * {@code tcp_try_undo_dsack} 的三分支,命中后回滚 {@code cwnd/ssthresh}
     * 并记 {@code TCPFULLUNDO / TCPLOSSUNDO / TCPDSACKUNDO} MIB。
     *
     * @param sk     套接字
     * @param reason undo 触发原因
     * @return {@code true} 表示完成 undo、退出 RECOVERY / LOSS
     */
    default boolean tryUndo(TcpSock sk, UndoReason reason) {
        throw new UnsupportedOperationException("LossRecovery.tryUndo not wired yet");
    }

    /**
     * ACK 上下文(占位类型) — 后续专项 phase 落实字段。
     */
    interface AckContext {
        long snd_una();
    }

    /**
     * undo 触发原因枚举 — 对应 Linux {@code TCPFULLUNDO / TCPLOSSUNDO / TCPDSACKUNDO /
     * TCPSPURIOUSRTOS} 的 MIB 口径。
     */
    enum UndoReason {
        /** RECOVERY 期间 TSECR 证明全部重传均为冗余。 */
        FULL_TSECR,
        /** LOSS 期间 TSECR 证明 RTO 为伪触发。 */
        LOSS_TSECR,
        /** DSACK 抵消所有重传副本。 */
        DSACK,
        /** F-RTO(RFC 5682)在 LOSS 期间首个 ACK 时判定 RTO 伪触发。 */
        FRTO
    }
}
