package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;

/**
 * CUBIC 拥塞控制(RFC 9438)— Linux 默认 CC 算法。
 *
 * <p>对齐 Linux {@code net/ipv4/tcp_cubic.c}。简化版:
 * <ul>
 *   <li>cubic 曲线增长 + TCP friendliness shadow</li>
 *   <li>loss reaction:{@code ssthresh = cwnd * BETA}({@code BETA = 0.7})</li>
 *   <li>不实现 Hystart++ / fast convergence(后续可补)</li>
 *   <li>不实现 RFC8312 历史 fixed-point 优化(Java double 直接算 K)</li>
 * </ul>
 *
 * <p><b>per-instance 状态</b>:每个 {@link TcpSock} 应该装载独立的
 * {@code CubicCongestionControl} 实例(不能像 NewReno 那样 singleton),因为
 * {@code lastMaxCwnd / epochStartUs / K / bicOrigin / tcpCwnd / ackCnt} 都是
 * per-connection 的。stack 配置时调
 * {@code sock.sender().congestionControl(new CubicCongestionControl())}。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cubic.c">Linux tcp_cubic.c</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9438">RFC 9438 CUBIC</a>
 */
public final class CubicCongestionControl implements TcpCongestionControl {

    // 算法常量
    /** cubic 形状参数 C(RFC 9438 §5.1)。 */
    private static final double C = 0.4;
    /** Multiplicative decrease factor β(RFC 9438 §4.5)。 */
    private static final double BETA = 0.7;
    /**
     * Fast convergence 系数:{@code (2 - BETA) / 2 = 0.65}。
     * loss 时 {@code cwnd < lastMaxCwnd} 触发,把 lastMaxCwnd 拉到 cwnd × 0.65,
     * 让新流更快"爬到"上次到顶位置,达成多流公平收敛(对齐 RFC 9438 §4.6)。
     */
    private static final double FAST_CONVERGENCE_FACTOR = (2.0 - BETA) / 2.0;
    /**
     * Hystart++ RTT delay 阈值下限(微秒)— RFC 9406 §4.2 MIN_RTT_THRESH。
     * 实际阈值为动态:{@code max(MIN, min(MAX, sample_min / 8))}。
     */
    private static final long HYSTART_RTT_THRESH_MIN_US = 4_000L;
    /** Hystart++ RTT delay 阈值上限(微秒)— RFC 9406 §4.2 MAX_RTT_THRESH。 */
    private static final long HYSTART_RTT_THRESH_MAX_US = 16_000L;
    /** Hystart++ 一个 round 内最少 RTT 样本数(避免抖动误判)。 */
    private static final int HYSTART_MIN_SAMPLES = 8;

    // Per-connection 状态
    /** 上次 loss 时的 cwnd 快照(段)。 */
    private long lastMaxCwnd;
    /** 进入新 epoch 的时戳(微秒);0 表示需重置。 */
    private long epochStartUs;
    /** 到达 cwnd_max 所需的时间(秒) — cubic 曲线参数。 */
    private double K;
    /** cubic 曲线"原点"(段)。 */
    private long bicOrigin;
    /** TCP friendliness 模拟的 Reno cwnd(段)。 */
    private long tcpCwnd;
    /** ACK 累计 — 达到 {@code cnt} 后 cwnd++。 */
    private int ackCnt;
    /**
     * Reno-friendly W_est 累加器(RFC 9438 §4.3):每 W_est 个 ACK 累计后 W_est++,
     * 等价于"每 RTT 增 1 段"。
     */
    private int renoAckCnt;

    // Hystart++ 状态(简化版)
    /** Hystart++ 是否已强制退出 slow start(本 epoch)。 */
    private boolean hystartFound;
    /** 上一个 round 的最小 RTT(微秒);0 表示无样本。 */
    private long hystartLastRoundMinRttUs;
    /** 当前 round 的最小 RTT(微秒);Long.MAX_VALUE 表示尚未采样。 */
    private long hystartCurrRoundMinRttUs = Long.MAX_VALUE;
    /** 当前 round 的 RTT 样本数 — 凑足 HYSTART_MIN_SAMPLES 才判定。 */
    private int hystartCurrRoundSamples;
    /** 当前 round 结束时的 sndNxt(到达即开新 round)— 0 表示未初始化。 */
    private int hystartRoundEnd;

    @Override
    public void init(TcpSock sock) {
        lastMaxCwnd = 0L;
        epochStartUs = 0L;
        K = 0.0;
        bicOrigin = 0L;
        tcpCwnd = 0L;
        ackCnt = 0;
        renoAckCnt = 0;
        hystartReset();
    }

    private void hystartReset() {
        hystartFound = false;
        hystartLastRoundMinRttUs = 0L;
        hystartCurrRoundMinRttUs = Long.MAX_VALUE;
        hystartCurrRoundSamples = 0;
        hystartRoundEnd = 0;
    }

    @Override
    public void onAck(TcpSock sock, RateSample rs) {
        Sender s = sock.sender();
        TcpSock.CongestionState st = s.congestionState();

        // RECOVERY 期间(含 dupack 与 partial ACK):PRR 接管 cwnd 调整,
        // 替代经典 NewReno-style "dupack inflation cwnd++"。
        if (st == TcpSock.CongestionState.RECOVERY) {
            Prr.onAck(sock, rs, 1);
            return;
        }
        if (st == TcpSock.CongestionState.LOSS) {
            // Loss 中持续累计 ACK 但未退出,不动 cwnd
            return;
        }
        if (rs.ackedPackets <= 0) return;

        // Slow start:cwnd += newlyAcked,Hystart++ 监测 RTT 上升提前退出
        if (s.cwnd() < s.ssthresh()) {
            // Hystart++ round 推进
            if (rs.rttUs > 0L) {
                if (hystartRoundEnd == 0) {
                    hystartRoundEnd = s.sndNxt();
                }
                if (rs.rttUs < hystartCurrRoundMinRttUs) {
                    hystartCurrRoundMinRttUs = rs.rttUs;
                }
                hystartCurrRoundSamples++;

                // round 结束:sndUna 已跨过 round_end → 评估 + rotate
                if (com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence
                        .after(s.sndUna(), hystartRoundEnd)
                        || s.sndUna() == hystartRoundEnd) {
                    // RFC 9406 §4.2 动态阈值:max(MIN, min(MAX, sample_min / 8))
                    long rttThresh = Math.max(HYSTART_RTT_THRESH_MIN_US,
                            Math.min(HYSTART_RTT_THRESH_MAX_US, hystartCurrRoundMinRttUs / 8L));
                    if (!hystartFound
                            && hystartLastRoundMinRttUs > 0L
                            && hystartCurrRoundSamples >= HYSTART_MIN_SAMPLES
                            && hystartCurrRoundMinRttUs > hystartLastRoundMinRttUs + rttThresh) {
                        // RTT 显著上升 → 缓冲填满 → 提前退出 slow start
                        hystartFound = true;
                        s.ssthresh(s.cwnd());      // 让 cwnd ≥ ssthresh,下次进 CA
                    }
                    hystartLastRoundMinRttUs = hystartCurrRoundMinRttUs;
                    hystartCurrRoundMinRttUs = Long.MAX_VALUE;
                    hystartCurrRoundSamples = 0;
                    hystartRoundEnd = s.sndNxt();
                }
            }
            // 即便 Hystart++ 已触发,本次 ACK 仍允许 cwnd += newlyAcked(下次 ACK 进 CA)
            s.cwnd(s.cwnd() + rs.ackedPackets);
            return;
        }

        // Congestion avoidance:cubic 曲线
        long cwnd = s.cwnd();
        long nowUs = System.nanoTime() / 1_000L;

        // 重置 epoch:第一次进入 CA 或 loss 后
        if (epochStartUs == 0L) {
            epochStartUs = nowUs;
            if (lastMaxCwnd <= cwnd) {
                K = 0.0;
                bicOrigin = cwnd;
            } else {
                // K = cbrt((cwnd_max - cwnd) / C)
                K = Math.cbrt((double) (lastMaxCwnd - cwnd) / C);
                bicOrigin = lastMaxCwnd;
            }
            ackCnt = 0;
            tcpCwnd = cwnd;
            renoAckCnt = 0;
        }

        // 当前 cubic target:bic_origin + C * (t - K)^3
        double tSec = (nowUs - epochStartUs) / 1_000_000.0;
        double offs = tSec - K;
        long target = bicOrigin + (long) (C * offs * offs * offs);

        // 计算 cnt:cwnd 段后 cwnd++
        long cnt;
        if (target > cwnd) {
            cnt = cwnd / Math.max(target - cwnd, 1L);
        } else {
            cnt = 100L * cwnd;       // target ≤ cwnd:几乎不增长
        }

        // Reno-friendly W_est 增长(RFC 9438 §4.3):每 W_est ACK 增 1 段,等价 Reno
        // 每 RTT +1 段。原 v2 简化版直接 += newlyAcked 等于每 RTT 翻倍,过激;改为
        // ack_cnt / W_est 累加器。
        renoAckCnt += rs.ackedPackets;
        while (tcpCwnd > 0L && renoAckCnt >= tcpCwnd) {
            renoAckCnt -= tcpCwnd;
            tcpCwnd++;
        }
        // 与 cubic target 取较快者(Reno 友好性:cubic 慢于 Reno 时跟 Reno)
        if (tcpCwnd > cwnd) {
            long tcpDelta = tcpCwnd - cwnd;
            if (tcpDelta > 0L) {
                long tcpCnt = cwnd / tcpDelta;
                if (tcpCnt < cnt) cnt = tcpCnt;
            }
        }
        if (cnt < 2L) cnt = 2L;       // 防止 cwnd 单 ACK 暴增

        ackCnt += rs.ackedPackets;
        if (ackCnt >= cnt) {
            s.cwnd(s.cwnd() + 1);
            ackCnt = 0;
        }
    }

    @Override
    public int ssthresh(TcpSock sock) {
        int cwnd = sock.cwnd();
        // Fast convergence(RFC 9438 §4.6):若 cwnd 还没爬到上次的 lastMaxCwnd 就又丢,
        // 把 lastMaxCwnd 拉到 cwnd × 0.65 让出位置,加快多流公平收敛。
        if (lastMaxCwnd > 0 && cwnd < lastMaxCwnd) {
            lastMaxCwnd = (long) (cwnd * FAST_CONVERGENCE_FACTOR);
        } else {
            lastMaxCwnd = cwnd;
        }
        // 下次进 CA 重置 epoch
        epochStartUs = 0L;
        // Hystart++ 状态在每次 loss 后清(下次进入 slow start 重新探测)
        hystartReset();
        return Math.max((int) (cwnd * BETA), 2);
    }

    @Override
    public int undoCwnd(TcpSock sock) {
        Sender s = sock.sender();
        // 与 NewReno 同语义:不让自然增长被压回
        return Math.max(s.priorCwnd(), s.cwnd());
    }

    @Override
    public void onStateChange(TcpSock sock,
                              TcpSock.CongestionState oldS,
                              TcpSock.CongestionState newS) {
        Sender s = sock.sender();
        if (newS == TcpSock.CongestionState.RECOVERY
                && (oldS == TcpSock.CongestionState.OPEN
                        || oldS == TcpSock.CongestionState.DISORDER)) {
            // 对齐 Linux tcp_enter_recovery + tcp_init_cwnd_reduction:
            //   cwnd 在入口保持 prior_cwnd,由 PRR 在每个 ACK 平滑下降到 ssthresh。
            //   priorCwnd / priorSsthresh 已由 Sender.tcpInitUndo 快照,ssthresh 已设。
            Prr.enterRecovery(sock);
            // CUBIC 自身 epoch 重置(下次 CA 重新算 K / bicOrigin)
            epochStartUs = 0L;
        } else if (newS == TcpSock.CongestionState.LOSS) {
            // RTO 进 Loss:cwnd = 1
            s.cwnd(1);
            epochStartUs = 0L;
        } else if (newS == TcpSock.CongestionState.OPEN
                && oldS == TcpSock.CongestionState.RECOVERY) {
            // 退出 Recovery:cwnd = ssthresh
            s.cwnd(s.ssthresh());
        }
    }
}
