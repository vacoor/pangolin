package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * {@link LossRecovery} 的默认实现骨架 — NewReno(RFC 6582) + RACK(RFC 8985)。
 *
 * <p>对齐 Linux 内核缺省的 {@code tcp_fastretrans_alert} + {@code tcp_rack_mark_lost}
 * 组合路径:dupack ≥3 / SACK-based 快速重传 + RACK 时间窗补丁。
 *
 * <p><b>本提交不接通主路径</b> — 所有方法均抛 {@link UnsupportedOperationException},
 * 现有 NewReno 恢复逻辑仍内嵌在 {@link TcpRetransmitter} / {@link TcpAck} /
 * {@link TcpSock} 中,等后续专项 phase 把主路径 delegate 到本类再填充实现。
 */
public final class ClassicRecovery implements LossRecovery {

    /** 单例 — 同一 JVM 内仅存一份,对齐 {@link TcpRetransmitter#INSTANCE} 的风格。 */
    public static final ClassicRecovery INSTANCE = new ClassicRecovery();

    private ClassicRecovery() {}
}
