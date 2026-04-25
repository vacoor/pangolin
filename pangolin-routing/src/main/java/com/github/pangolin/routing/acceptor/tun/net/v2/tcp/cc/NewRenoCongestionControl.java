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
        Sender s = sock.sender();
        TcpSock.CongestionState st = s.congestionState();

        // Recovery / Loss 不在此处动 cwnd:
        //   - Recovery 由 Sender 直接调 Prr.onAck 驱动 cwnd(对齐 Linux tcp_cwnd_reduction
        //     在 tcp_input.c 的 fastretrans_alert 路径,而非 cong_avoid plugin)
        //   - Loss 期保持 cwnd=1 直到状态退出,无自然增长
        if (st == TcpSock.CongestionState.RECOVERY
                || st == TcpSock.CongestionState.LOSS) {
            return;
        }

        // 正常 ACK 增长(OPEN / DISORDER):slow start / CA
        if (rs.ackedPackets <= 0) return;

        if (s.cwnd() < s.ssthresh()) {
            // Slow start:cwnd += newlyAcked
            s.cwnd(s.cwnd() + rs.ackedPackets);
        } else {
            // Congestion Avoidance:Reno 风格按段累加,够 cwnd 段后 +1
            int cnt = s.caIncrCounter() + rs.ackedPackets;
            if (cnt >= s.cwnd()) {
                s.cwnd(s.cwnd() + 1);
                cnt = 0;
            }
            s.caIncrCounter(cnt);
        }
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

    @Override
    public void onStateChange(TcpSock sock,
                              TcpSock.CongestionState oldS,
                              TcpSock.CongestionState newS) {
        Sender s = sock.sender();
        if (newS == TcpSock.CongestionState.LOSS) {
            // 进 Loss:cwnd = 1(对齐 Linux tcp_enter_loss)
            s.cwnd(1);
        } else if (newS == TcpSock.CongestionState.OPEN
                && oldS == TcpSock.CongestionState.RECOVERY) {
            // 退出 Recovery:cwnd = ssthresh
            s.cwnd(s.ssthresh());
        }
        // OPEN ↔ DISORDER / OPEN → RECOVERY / LOSS → OPEN:NewReno 自身不动 cwnd
        // RECOVERY 入口的 PRR 计数器重置已由 Sender 直接调 Prr.enterRecovery 完成
    }
}
