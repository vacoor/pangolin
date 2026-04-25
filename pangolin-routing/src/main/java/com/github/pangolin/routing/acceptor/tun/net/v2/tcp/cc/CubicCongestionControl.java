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

    @Override
    public void init(TcpSock sock) {
        lastMaxCwnd = 0L;
        epochStartUs = 0L;
        K = 0.0;
        bicOrigin = 0L;
        tcpCwnd = 0L;
        ackCnt = 0;
    }

    @Override
    public void onAck(TcpSock sock, RateSample rs) {
        Sender s = sock.sender();
        TcpSock.CongestionState st = s.congestionState();

        // RECOVERY 期间 dupack inflation(与 NewReno 同语义):cwnd++
        if (st == TcpSock.CongestionState.RECOVERY && rs.ackedPackets == 0) {
            s.cwnd(s.cwnd() + 1);
            return;
        }
        if (rs.ackedPackets <= 0) return;
        if (st == TcpSock.CongestionState.RECOVERY
                || st == TcpSock.CongestionState.LOSS) {
            // Recovery / Loss 内不动 cwnd,等状态退出后走自然增长
            return;
        }

        // Slow start:cwnd += newlyAcked
        if (s.cwnd() < s.ssthresh()) {
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

        // TCP friendliness:模拟 Reno 增长,与 cubic 取较快者
        tcpCwnd += rs.ackedPackets;
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
        // 进 Recovery / Loss 时记录 lastMaxCwnd,下次 CA 用作 K 参数
        lastMaxCwnd = cwnd;
        // 下次进 CA 重置 epoch
        epochStartUs = 0L;
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
            // Fast Retransmit 入口:cwnd = ssthresh + 3(NewReno 风格 dupack inflation)
            s.cwnd(s.ssthresh() + 3);
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
