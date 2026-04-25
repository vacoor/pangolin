package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;

/**
 * NewReno(RFC 5681 / RFC 6582)拥塞控制实现 — v2 默认 CC 算法。
 *
 * <p><b>本 Phase 1a 范围(2026-04)</b>:仅承载{@link #ssthresh} 与 {@link #undoCwnd}
 * 两个公式;{@link #onAck} 与 {@link #onStateChange} 暂留 no-op,由 {@code Sender}
 * 内联保留 NewReno 的 cwnd 增长(slow start / congestion avoidance /
 * Recovery dupack inflation / 进 Loss 的 cwnd=1)。后续 Phase 6/7 引入 CUBIC /
 * BBR 时,会把这些 cwnd 操作彻底迁到 SPI,使所有算法走同一接口。当前阶段先
 * 接通 SPI 形态,确保接口设计在落 CUBIC / BBR 时不需再改。
 *
 * <p>公式参考 Linux {@code tcp_reno_ssthresh} / {@code tcp_reno_undo_cwnd}:
 * <pre>
 *   ssthresh = max(cwnd / 2, 2);
 *   undoCwnd = max(prior_cwnd, cwnd);
 * </pre>
 *
 * <p>{@code priorCwnd} 由 {@code Sender.tcpInitUndo} 在进入 Recovery / Loss 前
 * 快照,作为伪触发回滚基线;若自然增长已经超过 priorCwnd(罕见),取 max 不让
 * undo 把更高的 cwnd 压回去。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">Linux tcp_reno_ssthresh / tcp_reno_undo_cwnd</a>
 */
public final class NewRenoCongestionControl implements TcpCongestionControl {

    /** 进程级单例 — NewReno 自身无 per-connection 私有状态(全部由 {@code Sender} 持有)。 */
    public static final NewRenoCongestionControl INSTANCE = new NewRenoCongestionControl();

    private NewRenoCongestionControl() {}

    @Override
    public void onAck(TcpSock sock, RateSample rs) {
        // Phase 1a: cwnd 增长仍由 Sender.onAckedByCc 内联处理,本方法 no-op。
        // 当 Phase 6 引入 CUBIC 时,会把 NewReno 的 slow start / CA 也迁到这里。
    }

    @Override
    public int ssthresh(TcpSock sock) {
        // 对齐 Linux tcp_reno_ssthresh:max(cwnd >> 1, 2)。
        return Math.max(sock.cwnd() / 2, 2);
    }

    @Override
    public int undoCwnd(TcpSock sock) {
        // 对齐 Linux tcp_reno_undo_cwnd:max(prior_cwnd, cwnd)。
        // 取 max 避免 undo 把已自然增长的 cwnd 反向压低。
        Sender s = sock.sender();
        return Math.max(s.priorCwnd(), s.cwnd());
    }
}
