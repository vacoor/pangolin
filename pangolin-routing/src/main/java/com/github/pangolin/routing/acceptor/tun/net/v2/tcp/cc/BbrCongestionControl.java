package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;

/**
 * BBR(Bottleneck Bandwidth and RTT)拥塞控制 — 模型驱动算法。
 *
 * <p>对齐 Linux {@code net/ipv4/tcp_bbr.c}(BBRv1)。简化版:
 * <ul>
 *   <li>BtlBw 估计:窗内最大 deliveryRate(简化为 last-update + expiry,不做严格 minmax)</li>
 *   <li>RTprop 估计:窗内最小 RTT(10s 滑窗)</li>
 *   <li>pacing rate = BtlBw × pacing_gain</li>
 *   <li>cwnd target = BDP × cwnd_gain;BDP = BtlBw × RTprop</li>
 *   <li>4 状态机:StartUp → Drain → ProbeBW → ProbeRTT(每 10s)</li>
 * </ul>
 *
 * <p><b>per-instance 状态</b>:每个 {@link TcpSock} 装独立 BBR 实例。
 *
 * <p><b>简化点</b>(后续可补):
 * <ul>
 *   <li>不实现 BBRv2 的 ECN / 启发式</li>
 *   <li>RateSample.sendElapsedUs / ackElapsedUs 在 Phase 1b 留 0,Phase 7 BBR
 *       使用时直接读 {@code sock.srttUs()} 作为 interval 估算 fallback</li>
 *   <li>不实现 BBR 的 packet conservation(进 Recovery 时保留 cwnd)严格逻辑</li>
 * </ul>
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_bbr.c">Linux tcp_bbr.c</a>
 * @see <a href="https://datatracker.ietf.org/doc/draft-cardwell-iccrg-bbr-congestion-control/">BBR Internet-Draft</a>
 */
public final class BbrCongestionControl implements TcpCongestionControl {

    public enum Phase { STARTUP, DRAIN, PROBE_BW, PROBE_RTT }

    // 算法常量
    /** StartUp / Drain pacing gain:2/ln2 ≈ 2.885;高速探测带宽。 */
    private static final double STARTUP_PACING_GAIN = 2.0 / Math.log(2.0);
    /** Drain pacing gain:1 / STARTUP_GAIN ≈ 0.346;排空 startup queue。 */
    private static final double DRAIN_PACING_GAIN = 1.0 / STARTUP_PACING_GAIN;
    /** ProbeBW 8 阶 cycling gain。 */
    private static final double[] PROBE_BW_GAINS = {
            1.25, 0.75, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0
    };
    /** StartUp / Drain cwnd gain。 */
    private static final double STARTUP_CWND_GAIN = STARTUP_PACING_GAIN;
    /** ProbeBW cwnd gain — 2× BDP 容纳 reordering / pacing 抖动。 */
    private static final double PROBE_BW_CWND_GAIN = 2.0;
    /** BtlBw 滑窗(微秒) — 10 秒(对齐 Linux BBRv1 ~10 RTT,简化为固定 10s)。 */
    private static final long BTLBW_FILTER_LEN_US = 10_000_000L;
    /** RTprop 滑窗(微秒) — 10 秒。 */
    private static final long RTPROP_FILTER_LEN_US = 10_000_000L;
    /** ProbeRTT 触发间隔(微秒) — 10 秒不见更小 RTT 就探测。 */
    private static final long PROBE_RTT_INTERVAL_US = 10_000_000L;
    /** ProbeRTT 持续时长(微秒) — 200ms。 */
    private static final long PROBE_RTT_DURATION_US = 200_000L;
    /** ProbeRTT 期间 cwnd cap(段)。 */
    private static final int PROBE_RTT_CWND = 4;
    /** 最小 cwnd(段) — BBR 不允许 cwnd 塌缩。 */
    private static final int MIN_CWND = 4;
    /** StartUp 退出门槛:连续 3 轮 BtlBw 增长 < 25% 视为带宽到顶。 */
    private static final int STARTUP_FULL_BW_THRESH_ROUNDS = 3;
    /** BtlBw 显著增长阈值:>= 1.25× 才算"还在涨"。 */
    private static final double STARTUP_FULL_BW_GAIN = 1.25;

    // Per-connection 状态
    private Phase phase = Phase.STARTUP;
    /** BtlBw 滑窗最大值过滤器(BBR 严格语义,替代 last-update + expiry)。 */
    private final WinMaxFilter btlBwFilter = new WinMaxFilter();
    /** 上一次 BtlBw 样本,用于 StartUp 退出判定。 */
    private long lastBtlBwBps;
    /** StartUp 期间已观察 BtlBw 不再涨的连续轮数。 */
    private int startupFullBwCount;
    /** RTprop 估计(微秒);Long.MAX_VALUE 表示未采样。 */
    private long rtPropUs = Long.MAX_VALUE;
    /** RTprop 过期时戳(微秒);超过则进入 ProbeRTT。 */
    private long rtPropExpiryUs;
    /** ProbeBW 当前 cycle index(0..7)。 */
    private int probeBwCycleIdx;
    /** ProbeBW 当前 cycle 起始时戳(微秒)。 */
    private long probeBwCycleStartUs;
    /** ProbeRTT 进入时戳(微秒) — cwnd 实际降到 PROBE_RTT_CWND 之后启动 200ms 计时器。 */
    private long probeRttEnterUs;
    /** ProbeRTT 退出时戳;0 表示 cwnd 还未 cap 到位。 */
    private long probeRttDoneUs;
    /** ProbeRTT 期间看到的有效 RTT 样本数 — 退出条件之一。 */
    private int probeRttRttSamplesSeen;

    @Override
    public void init(TcpSock sock) {
        phase = Phase.STARTUP;
        btlBwFilter.reset(0L, 0L);
        lastBtlBwBps = 0L;
        startupFullBwCount = 0;
        rtPropUs = Long.MAX_VALUE;
        rtPropExpiryUs = 0L;
        probeBwCycleIdx = 0;
        probeBwCycleStartUs = 0L;
        probeRttEnterUs = 0L;
        probeRttDoneUs = 0L;
        probeRttRttSamplesSeen = 0;
        sock.sender().pacingRateBps(0L);
    }

    @Override
    public void onAck(TcpSock sock, RateSample rs) {
        Sender s = sock.sender();
        TcpSock.CongestionState st = s.congestionState();

        // BBR 在 Recovery / Loss 中不暴力压 cwnd(packet conservation 简化版:不动)
        // 但仍正常更新 BtlBw / RTprop estimate
        long nowUs = System.nanoTime() / 1_000L;
        int mss = Math.max(sock.mss(), 1);

        // (1) RTprop 滑窗更新
        long rttUs = rs.rttUs > 0L ? rs.rttUs : sock.srttUs();
        if (rttUs > 0L) {
            if (rttUs < rtPropUs || nowUs > rtPropExpiryUs) {
                rtPropUs = rttUs;
                rtPropExpiryUs = nowUs + RTPROP_FILTER_LEN_US;
            }
            // ProbeRTT 退出条件之一:在 ProbeRTT 期间至少看到 1 个新 RTT 样本
            if (phase == Phase.PROBE_RTT) {
                probeRttRttSamplesSeen++;
            }
        }

        // (2) BtlBw 估计:deliveryRate = (delivered - priorDelivered) / interval
        //     interval = max(sendElapsedUs, ackElapsedUs)。精细化 1 后 cleanRtxQueue
        //     真聚合;若仍为 0(纯 dup ACK 等)则 fallback srttUs。
        long interval = Math.max(rs.sendElapsedUs, rs.ackElapsedUs);
        if (interval == 0L) interval = sock.srttUs();
        int deliveredDelta = rs.delivered - rs.priorDelivered;
        if (interval > 0L && deliveredDelta > 0
                && !rs.isAckedRetrans && !rs.appLimited) {
            long deliveredBytes = (long) deliveredDelta * mss;
            long sampleBps = (deliveredBytes * 1_000_000L) / interval;
            // 精细化 2:严格 windowed-max filter(对齐 Linux BBRv1)
            btlBwFilter.update(BTLBW_FILTER_LEN_US, nowUs, sampleBps);
        }

        // (3) 状态机
        advanceStateMachine(sock, nowUs);

        // (4) 计算 pacing rate(读 windowed-max BtlBw)
        long btlBw = btlBwFilter.max();
        double pacingGain = currentPacingGain();
        long pacingBps = (long) (btlBw * pacingGain);
        s.pacingRateBps(pacingBps);

        // (5) 计算 cwnd target = BDP × cwnd_gain
        if (btlBw > 0L && rtPropUs > 0L && rtPropUs != Long.MAX_VALUE) {
            long bdpBytes = (btlBw * rtPropUs) / 1_000_000L;
            int bdpSegs = (int) Math.max(bdpBytes / mss, 1L);
            double cwndGain = currentCwndGain();
            int targetCwnd;
            if (phase == Phase.PROBE_RTT) {
                targetCwnd = PROBE_RTT_CWND;
            } else {
                targetCwnd = (int) Math.max(bdpSegs * cwndGain, MIN_CWND);
            }
            // BBR 不允许 cwnd 直接塌缩;Recovery / Loss 期间保留当前(packet conservation)
            if (st != TcpSock.CongestionState.RECOVERY
                    && st != TcpSock.CongestionState.LOSS) {
                s.cwnd(targetCwnd);
            }
        }
    }

    private void advanceStateMachine(TcpSock sock, long nowUs) {
        switch (phase) {
            case STARTUP:
                // 检测 BtlBw(滑窗 max)是否还在涨 ≥ 1.25×
                long btlBwNow = btlBwFilter.max();
                if (btlBwNow > 0L) {
                    if ((double) btlBwNow < STARTUP_FULL_BW_GAIN * lastBtlBwBps) {
                        startupFullBwCount++;
                    } else {
                        startupFullBwCount = 0;
                    }
                    lastBtlBwBps = btlBwNow;
                    if (startupFullBwCount >= STARTUP_FULL_BW_THRESH_ROUNDS) {
                        phase = Phase.DRAIN;
                    }
                }
                break;
            case DRAIN:
                // Drain 完成条件:inflight ≤ BDP(对齐 Linux bbr_check_drain)。
                // BDP = BtlBw × RTprop / 1e6;inflight 字节 = packets_out × MSS。
                long btlBwDrain = btlBwFilter.max();
                if (btlBwDrain > 0L
                        && rtPropUs > 0L && rtPropUs != Long.MAX_VALUE) {
                    long bdpBytes = (btlBwDrain * rtPropUs) / 1_000_000L;
                    long inflightBytes = (long) sock.packetsOut() * Math.max(sock.mss(), 1);
                    if (inflightBytes <= bdpBytes) {
                        phase = Phase.PROBE_BW;
                        probeBwCycleStartUs = nowUs;
                        probeBwCycleIdx = 0;
                    }
                }
                break;
            case PROBE_BW:
                // 每 RTprop 切换一次 cycle gain
                if (rtPropUs > 0L && rtPropUs != Long.MAX_VALUE
                        && nowUs - probeBwCycleStartUs >= rtPropUs) {
                    probeBwCycleIdx = (probeBwCycleIdx + 1) % PROBE_BW_GAINS.length;
                    probeBwCycleStartUs = nowUs;
                }
                // 检查是否需要进入 ProbeRTT
                if (nowUs > rtPropExpiryUs && rtPropExpiryUs > 0L) {
                    phase = Phase.PROBE_RTT;
                    probeRttEnterUs = nowUs;
                    probeRttDoneUs = 0L;        // 待 cwnd 实际降到 PROBE_RTT_CWND 后再设
                    probeRttRttSamplesSeen = 0;
                }
                break;
            case PROBE_RTT:
                // ProbeRTT 退出条件(对齐 Linux bbr_check_probe_rtt_done):
                //   1) cwnd 已降到 PROBE_RTT_CWND(cwnd 调整在 onAck 末尾完成)
                //   2) 至少持续 PROBE_RTT_DURATION_US (200ms)
                //   3) 至少看到 1 个新 RTT 样本(即 rs.rttUs > 0)
                if (probeRttDoneUs == 0L && sock.cwnd() <= PROBE_RTT_CWND) {
                    // 第一次满足 cwnd cap,启动 200ms 计时器
                    probeRttDoneUs = nowUs + PROBE_RTT_DURATION_US;
                }
                if (probeRttDoneUs != 0L
                        && nowUs >= probeRttDoneUs
                        && probeRttRttSamplesSeen >= 1) {
                    phase = Phase.PROBE_BW;
                    probeBwCycleStartUs = nowUs;
                    probeBwCycleIdx = 0;
                    rtPropExpiryUs = nowUs + RTPROP_FILTER_LEN_US;
                }
                break;
        }
    }

    private double currentPacingGain() {
        switch (phase) {
            case STARTUP: return STARTUP_PACING_GAIN;
            case DRAIN:   return DRAIN_PACING_GAIN;
            case PROBE_BW:
                return PROBE_BW_GAINS[probeBwCycleIdx];
            case PROBE_RTT:
                return 1.0;
            default: return 1.0;
        }
    }

    private double currentCwndGain() {
        switch (phase) {
            case STARTUP:
            case DRAIN:
                return STARTUP_CWND_GAIN;
            case PROBE_BW:
                return PROBE_BW_CWND_GAIN;
            case PROBE_RTT:
                return 1.0;
            default: return 1.0;
        }
    }

    @Override
    public int ssthresh(TcpSock sock) {
        // BBR 不依赖 ssthresh 做拥塞反应,返回当前 cwnd 不变(让 Sender 状态切换跑通)
        return Math.max(sock.cwnd(), 2);
    }

    @Override
    public int undoCwnd(TcpSock sock) {
        // BBR 不像 NewReno 那样维护 priorCwnd 用 undo;返回当前 cwnd
        return sock.cwnd();
    }

    @Override
    public void onStateChange(TcpSock sock,
                              TcpSock.CongestionState oldS,
                              TcpSock.CongestionState newS) {
        // BBR 不像 NewReno 那样在 Recovery/Loss 入口暴力压 cwnd;
        // packet conservation 由 onAck 路径里"Recovery/Loss 不动 cwnd"实现。
        // 仅在 RTO 时把 phase 重置到 STARTUP(让重新探测带宽)。
        if (newS == TcpSock.CongestionState.LOSS) {
            phase = Phase.STARTUP;
            startupFullBwCount = 0;
            lastBtlBwBps = 0L;
            // 保留 btlBwFilter / rtPropUs(BBR 模型不丢,即使 RTO 也不重置 filter)
        }
    }

    @Override
    public long pacingRateBps(TcpSock sock) {
        return sock.sender().pacingRateBps();
    }

    /** 暴露当前 BBR phase(测试 / 调试用)。 */
    public Phase phase() {
        return phase;
    }

    /** 暴露当前 BtlBw 估计(从 windowed-max filter 读)。 */
    public long btlBwBps() {
        return btlBwFilter.max();
    }

    /** 暴露当前 RTprop 估计。 */
    public long rtPropUs() {
        return rtPropUs;
    }
}
