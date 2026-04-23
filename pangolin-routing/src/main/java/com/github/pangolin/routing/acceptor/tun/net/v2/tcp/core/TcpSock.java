package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;

import java.util.HashMap;
import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;

public class TcpSock extends SockCommon {
    /** CA 状态枚举 — 对应 Linux {@code tp->ca_state} 子集({@code CA_Open/Recovery/Loss})。 */
    enum CongestionState {
        OPEN,
        RECOVERY,
        LOSS
    }

    private TcpConnection conn;
    private Channel channel;
    private Channel childChannel;
    private ChannelFutureListener childCloseListener;
    /**
     * 所属协议栈实例 — 由 {@link TcpMultiplexer#configure(TcpSock)} 注入,
     * 承载 per-stack 服务(retransmitter / MIB / policy 等)的入口。
     */
    private TcpMultiplexer multiplexer;
    /**
     * 发送侧聚合对象 — R2 抽 Sender 的目标。R2.0(骨架)期间只是 facade,
     * 状态仍存在 TcpSock 自身;R2.3 起字段下沉到本对象。由
     * {@link TcpMultiplexer#configure(TcpSock)} 与 {@code multiplexer} 同时注入。
     */
    private Sender sender;
    /**
     * 接收侧聚合对象 — R3 抽 Receiver 的目标。R3.0(骨架)期间只是 facade,
     * 状态仍存在 TcpSock 自身;R3.2 起字段下沉到本对象。
     */
    private Receiver receiver;
    /**
     * 挂在 sock 上的业务事件回调 — 由 {@link TcpSockInitializer#onEstablished} 注入。
     * 典型实现是 netty 子包的 {@code TcpChannel},或 ext.backend 子包的
     * {@code TcpPassthroughHandler}。不为 {@code null} 表示这条连接已装配业务层。
     */
    private TcpSockHandler handler;
    /**
     * 应用层 autoRead 反压标志 — {@code true} 时 {@code queue_and_out} 仍把数据放入
     * {@link TcpReceiveBuffer}(保持 rcv_nxt 推进 / ACK 正常),但不再 drain 到
     * userChannel,从而 {@code receiveWindow()} 自然收缩,对端感知反压。
     * 对齐 Linux "应用 syscall 未读 socket 导致 tp->rcv_wnd 缩窗" 语义。
     */
    // R3.2: rcvPaused 已物理迁到 Receiver
    private TcpSendBuffer sendBuffer;
    private TcpReceiveBuffer receiveBuffer;
    private TcpConnectionTimers timers;
    private final Map<ConnectionKey<?>, Object> attributes = new HashMap<>();
    private TcpConnectionState state;
    // R2.3: sndUna / sndNxt / writeSeq 已物理迁到 Sender
    // R3.2: rcvNxt 已物理迁到 Receiver
    // R2.3: sndWnd / maxWindow / sndWl1 / sndSml 已物理迁到 Sender
    // R3.2: rcvWnd / rcvWup 已物理迁到 Receiver
    private int rcvMss;
    private int mss;
    private int sndWscale;
    private int rcvWscale;
    private long bytesAcked;
    // R2.3: packetsOut 已物理迁到 Sender
    private int skShutdown;
    private int ackPending;
    private int skErr;
    private long lastOowAckTimeMs;
    /** Mirrors {@code inet_sock.tos}. */
    private int tos;
    /** RFC 5961 per-socket challenge-ACK accounting window start (ms) — {@code tp->challenge_timestamp}. */
    private long ipv4TcpChallengeTimestamp;
    /** RFC 5961 per-socket challenge ACKs emitted in current window — {@code tp->challenge_count}. */
    private int ipv4TcpChallengeCount;
    /** Delayed-ACK retry count — mirrors {@code icsk_ack.retry}. */
    private int icskAckRetry;
    /** Last data-segment length for {@code rcv_mss} tuning — mirrors {@code icsk_ack.last_seg_size}. */
    private int icskAckLastSegSize;
    /**
     * Last data-segment arrival time (ms) used for ATO / delayed-ACK decisions —
     * mirrors {@code icsk_ack.lrcvtime}.  Distinct from {@link #lastRecvTimeMs}
     * which tracks any-segment activity for keepalive.
     */
    private long icskAckLrcvtimeMs;
    private boolean timestampEnabled;
    private int recentTimestamp;
    /**
     * 最近一次刷新 {@code recentTimestamp} 的本地接收秒级时戳。
     * 对应 Linux {@code tcp_options_received.ts_recent_stamp} (get_seconds()),
     * 用于 PAWS 24 天陈旧分支 ({@code tcp_paws_check})。{@code 0} 表示尚未建立基线。
     */
    private int tsRecentStamp;
    private int quickAckCount;
    private long ackTimeoutMs;
    private int pingpongCount;
    private long lastRecvTimeMs;
    private long lastSendTimeMs;
    private long srttUs;
    private long rttvarUs;
    // R2.3: rtoBackoffShift 已物理迁到 Sender
    // R2.3: retransStamp 已物理迁到 Sender(对应 Linux tp->retrans_stamp)
    /**
     * 当前 recovery 阶段累计的重传段计数;收到覆盖它们的 ACK 时递减。
     * 对应 Linux {@code tp->undo_retrans},F-RTO 与 CC undo 路径依赖。
     */
    private int undoRetrans;
    /** 接收缓冲区上限(字节)— 对应 Linux {@code sk->sk_rcvbuf},用于 {@code tcp_full_space}。 */
    private int rcvBuf;
    /** 单次可通告窗口上限 — 对应 Linux {@code tp->window_clamp}。 */
    private int windowClamp;
    /** 接收侧慢启动阈值 — 对应 Linux {@code tp->rcv_ssthresh},限制当前可通告窗口。 */
    private int rcvSsthresh;
    /**
     * 缓存的发送侧当前有效 MSS(扣除 IP/TCP 头 + 扩展头,半窗夹紧后)。
     * 对应 Linux {@code tp->mss_cache};由 {@code tcp_sync_mss} 更新。
     */
    private int mssCache;
    /**
     * 最近一次驱动 {@code mssCache} 的 PMTU 值。对应 Linux {@code icsk->icsk_pmtu_cookie};
     * {@code tcp_current_mss} 在 {@code dstMtu != pmtuCookie} 时触发重新同步。
     */
    private int pmtuCookie;
    /**
     * TCP 头 + 已协商 ESTABLISHED 选项的总长度(字节)。对应 Linux {@code tp->tcp_header_len},
     * 握手完成时定型(20,时戳协商后 +12);运行期 {@code tcp_established_options} 返回的
     * 长度与之存在 delta 时从 MSS 扣减。
     */
    private int tcpHeaderLen;
    /**
     * 当前路径 MTU(字节),外部 ICMP Frag Needed / MTU 发现写入。对应 Linux
     * {@code dst_mtu(sk->sk_dst_cache)};{@code 0} 表示尚未获得任何路径 MTU 信息,
     * 此时 {@code tcp_current_mss} 跳过 PMTU 再同步分支。
     */
    private int dstMtu;
    /**
     * SACK 块已覆盖但尚未被累计 ACK 吸收的段数(非字节数)。
     * 对应 Linux {@code tp->sacked_out};由 {@code tcp_sacktag_write_queue} 增,
     * {@code tcp_clean_rtx_queue} 在释放带 {@code TCPCB_SACKED_ACKED} 位的段时减。
     */
    // R2.3: sackedOut 已物理迁到 Sender
    /**
     * 被 RACK / FACK-like 路径标记为 LOST 的段数(非字节数)。
     * 对应 Linux {@code tp->lost_out};由 {@code tcp_mark_skb_lost} 增,
     * {@code tcp_clean_rtx_queue} 释放带 {@code TCPCB_LOST} 位的段时减。
     */
    // R2.3: lostOut 已物理迁到 Sender
    /**
     * RACK 最近一次被 SACK 覆盖段的 {@code sentTimeUs}(对齐 Linux {@code tp->rack.mstamp})。
     * 新 SACK tagging 时单调向上刷新;判断更早发送但未被 SACK 的段是否可判 LOST。
     */
    private long rackMstamp;
    /**
     * RACK 对应上述段的 RTT 样本 (us),参与 reo_wnd 计算。目前未进一步细化:
     * 采 {@code sockTime - sentTimeUs} 近似。
     */
    private long rackRttUs;
    /**
     * RACK {@code reo_wnd} 的步长系数 — 对应 Linux {@code tp->rack.reo_wnd_steps}。
     * 初始 1;每当观察到 DSACK 事件(发送端视角)在 1 RTT 外被确认时递增,直至上限
     * {@link TcpConstants#TCP_RACK_RECOVERY_THRESH} × 16。{@link #tcpRackUpdateReoWnd}
     * 在 {@code reoWndPersist} 耗尽且无 DSACK 时衰减回 1。
     */
    private int rackReoWndSteps;
    /**
     * RACK {@code reo_wnd} 持续计数 — 对应 Linux {@code tp->rack.reo_wnd_persist}。
     * DSACK 事件后复位为 {@link TcpConstants#TCP_RACK_RECOVERY_THRESH}(16),每次
     * {@link #tcpRackUpdateReoWnd} 递减;到 0 时衰减 {@link #rackReoWndSteps} 回 1。
     */
    private int rackReoWndPersist;
    /**
     * 是否看到本轮 ACK 窗口内的 DSACK — 对应 Linux {@code tp->rack.dsack_seen}。
     * 由 {@code tcp_sacktag_write_queue} 识别 DSACK 首块时置位,
     * {@link #tcpRackUpdateReoWnd} 消费后清零。
     */
    private boolean rackDsackSeen;
    /**
     * 每段递增的"已投递"计数 — 对应 Linux {@code tp->delivered}。
     * 段被累计 ACK 或新 SACK 标记时 +1;发送(含重传)时会被 {@code tcp_rate_skb_sent}
     * 快照到 {@code TCP_SKB_CB(skb)->tx.delivered},RACK 1-RTT 门控据此计算时序。
     */
    private int delivered;
    /**
     * RACK 上次调整 {@code reo_wnd_steps} 时 {@link #delivered} 的快照 —
     * 对应 Linux {@code tp->rack.last_delivered}。
     * 1-RTT 门控:本 ACK 所确认段的 {@code tx.delivered} 早于该值时,属于同一 RTT
     * 的旧 DSACK,不再重复步进。
     */
    private int rackLastDelivered;
    /**
     * 每 ACK 瞬态 scratchpad:本 ACK 内"已投递段"的 {@code tx.delivered} 最大值 —
     * 对应 Linux {@code rate_sample.prior_delivered}。{@code tcpAck} 入口清零,
     * {@code tcp_clean_rtx_queue} / {@code tcp_sacktag_write_queue} 识别新投递段时
     * 刷新,{@link #tcpRackUpdateReoWnd} 据此判断 1-RTT 门控。
     */
    private int rackAckPriorDelivered;
    /**
     * 对齐 Linux {@code tp->rtt_min} — 以 Kathleen Nichols Windowed Filter 维护
     * 时间窗 {@link TcpConstants#TCP_MIN_RTT_WIN_SEC}(默认 300 秒)内的 RTT 最小值,
     * 单位微秒,时间坐标毫秒(与 {@code tcp_jiffies32} 一致)。{@link #addRttSample}
     * 每次采样喂入,{@link #minRttUs} 读回窗内 running min;RACK
     * {@code reo_wnd = min((min_rtt * steps) >> 2, srtt >> 3)} 依赖本字段。
     */
    private final com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.WinMinMax rttMinFilter
            = new com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.WinMinMax();
    private int linger2;
    private int probeBackoffShift;
    private int probesOut;
    private long probesTstampMs;
    private long userTimeoutMs;
    private long keepaliveTimeMs;
    private long keepaliveIntvlMs;
    private int keepaliveProbes;
    private boolean keepaliveEnabled;
    private Runnable probeTimerAction = () -> {};
    private Runnable keepaliveTimerAction = () -> {};
    // R2.3: cwnd / ssthresh 已物理迁到 Sender
    /**
     * cwnd 最近一次被 {@code tcp_cwnd_validate} 确认的毫秒时戳 —
     * 对应 Linux {@code tp->snd_cwnd_stamp}(单位 {@code tcp_jiffies32})。
     * app-limited 时判定是否到了 {@code tcp_cwnd_application_limited} 回收点。
     */
    // R2.3: sndCwndStampMs 已物理迁到 Sender
    /**
     * non-cwnd-limited 期间 packets_out 的最高水位 — 对应 Linux
     * {@code tp->snd_cwnd_used}。application-limited 回收时取
     * {@code max(snd_cwnd_used, tcp_init_cwnd)} 作为新 cwnd 下沿。
     */
    // R2.3: sndCwndUsed 已物理迁到 Sender
    /**
     * 当前窗口是否 cwnd 受限 — 对应 Linux {@code tp->is_cwnd_limited}。
     * 置位后持续到下一次 {@code tcp_cwnd_application_limited} 执行才清理。
     */
    // R2.3: isCwndLimited 已物理迁到 Sender
    private int dupacks;
    private int caIncrCounter;
    private CongestionState congestionState = CongestionState.OPEN;
    private int highSeq;
    // R2.3: tlpHighSeq 已物理迁到 Sender(对应 Linux tp->tlp_high_seq)
    /**
     * 下一次发 ACK 需要通告的 DSACK 块 {@code [dsackStart, dsackEnd)}
     * (对应 Linux {@code tp->duplicate_sack[0]})。{@code dsackStart == dsackEnd}
     * 表示无待发 DSACK。DSACK 发送后由 {@link #consumeDsack} 清零,保证只通告一次。
     */
    private int dsackStart;
    private int dsackEnd;
    /**
     * 进入 Recovery/Loss 前的 {@code cwnd} 快照 — 对应 Linux {@code tp->prior_cwnd}。
     * {@link #tcpTryUndoRecovery} 判定伪重传通过后,用它恢复 pre-loss 拥塞窗口。
     */
    // R2.3: priorCwnd / priorSsthresh 已物理迁到 Sender
    /**
     * 进入 Recovery/Loss 时的 {@code snd_una} 快照 — 对应 Linux {@code tp->undo_marker}。
     * 非 0 表示当前存在一次 undo 机会;{@link #tcpUndoCwndReduction} 执行后清零。
     */
    private int undoMarker;
    /**
     * F-RTO high mark — 对应 Linux {@code tp->high_seq}(在 F-RTO 语境下的快照):
     * RTO 触发瞬间 {@code snd_nxt} 的副本,作为后续 ACK 判定伪 RTO 的参照线。
     * {@code frtoCounter != 0} 时有效,{@link #tcpUndoCwndReduction} /
     * {@link #clearFrto} 或自然退出 CA_Loss 时清零。
     */
    private int frtoHighmark;
    /**
     * F-RTO 状态机 — 对应 Linux {@code tp->frto}:{@code 0} 表示未武装,
     * {@code 1} 表示本轮 RTO 已武装 F-RTO(等待首个 ACK 判定是否伪触发)。
     * 命中 {@link #tcpProcessFrto} 后清零,落入 {@link TcpMib#TCPSPURIOUSRTOS} 记账;
     * 未命中则在首次有效 ACK 后降级回 CA_Loss 常规路径。
     */
    private int frtoCounter;

    protected TcpSock() {
        this(null, false);
    }

    protected TcpSock(TcpConnection conn) {
        this(conn, true);
    }

    protected TcpSock(TcpConnection conn, boolean initializeExtensions) {
        super(conn == null ? null : conn.fourTuple());
        attach(conn, initializeExtensions);
    }

    protected TcpSock(FourTuple fourTuple) {
        super(fourTuple);
    }

    public static TcpSock from(TcpConnection conn) {
        return new TcpSock(conn, true);
    }

    public static TcpSock view(TcpConnection conn) {
        return new TcpSock(conn, false);
    }

    public static TcpSock createChild(Channel channel,
                                      Channel childChannel,
                                      FourTuple fourTuple,
                                      int sndUna,
                                      int sndNxt,
                                      int rcvNxt,
                                      int sndWnd,
                                      int rcvWnd,
                                      int mss,
                                      int sndWscale,
                                      int rcvWscale,
                                      boolean timestampEnabled,
                                      int recentTimestamp) {
        TcpSock sock = new TcpSock(fourTuple);
        // R2.3/R3.2/R4.1 前置装配:先 new Sender/Receiver 让后续字段 setter 能写进去。
        // TcpMultiplexer.configure 将检测 sender/receiver 非空后幂等跳过,不会覆盖。
        sock.sender(new Sender(sock));
        sock.receiver(new Receiver(sock));
        sock.channel = channel;
        sock.childChannel = childChannel;
        sock.sendBuffer = new TcpSendBuffer();
        sock.receiveBuffer = new TcpReceiveBuffer(channel.alloc());
        sock.timers = new TcpConnectionTimers();
        sock.state = TcpConnectionState.TCP_SYN_RECV;
        sock.sender().sndUna(sndUna);
        sock.sender().sndNxt(sndNxt);
        sock.sender().writeSeq(sndNxt);
        sock.receiver().rcvNxt(rcvNxt);
        sock.sender().sndWnd(sndWnd);
        sock.sender().maxWindow(sndWnd);
        sock.sender().sndWl1(0);
        sock.sender().sndSml(sndUna);
        sock.receiver().rcvWnd(rcvWnd);
        sock.receiver().rcvWup(rcvNxt);
        sock.rcvMss = mss;
        sock.mss = mss;
        sock.sndWscale = sndWscale;
        sock.rcvWscale = rcvWscale;
        sock.initInlineTcpState();
        sock.timestampEnabled = timestampEnabled;
        sock.recentTimestamp = recentTimestamp;
        // 子 sock 由 SYN 中 TSval 初始化 ts_recent,刷新时戳也在此点建立
        sock.tsRecentStamp = timestampEnabled ? TcpMultiplexer.nowSeconds() : 0;
        // 接收侧窗口预算初始化(对齐 Linux tcp_init_buffer_space):
        // rcvBuf / windowClamp 取当前通告窗口,rcvSsthresh 同步(无缩放时退化为 rcvWnd)
        sock.rcvBuf = Math.max(rcvWnd, TcpConstants.TCP_DEFAULT_RCV_BUF);
        sock.windowClamp = Math.max(rcvWnd, TcpConstants.TCP_DEFAULT_RCV_BUF);
        sock.rcvSsthresh = Math.max(rcvWnd, TcpConstants.TCP_DEFAULT_RCV_BUF);
        // PMTU / tcp_header_len 初始化(对齐 Linux tcp_create_openreq_child):
        //   tcp_header_len = 20 + (tstamp_ok ? TCPOLEN_TSTAMP_ALIGNED(12) : 0)
        //   mssCache 延迟到首次 syncMss;pmtuCookie/dstMtu 默认 0(无 PMTU 发现)
        sock.tcpHeaderLen = TcpConstants.TCP_MIN_HEADER_LEN
                + (timestampEnabled ? TcpConstants.TCP_TSOPT_WIRE_LEN : 0);
        sock.mssCache   = mss;
        sock.pmtuCookie = 0;
        sock.dstMtu     = 0;
        // R2.3: sackedOut / lostOut 默认 0(Sender 字段初值)
        sock.rackMstamp = 0L;
        sock.rackRttUs  = 0L;
        sock.rackReoWndSteps   = 1;
        sock.rackReoWndPersist = 0;
        sock.rackDsackSeen     = false;
        sock.delivered             = 0;
        sock.rackLastDelivered     = 0;
        sock.rackAckPriorDelivered = 0;
        sock.rttMinFilter.reset(0, TcpConstants.TCP_MIN_RTT_NO_SAMPLE);
        sock.linger2 = (int) TcpConstants.FIN_WAIT_2_TIMEOUT_MS;
        return sock;
    }

    public TcpConnection connection() {
        return conn;
    }

    public boolean hasConnection() {
        return channel != null && sendBuffer != null && receiveBuffer != null;
    }

    public boolean hasBackendChannel() {
        return childChannel != null && childChannel.isActive();
    }

    public void attach(TcpConnection conn) {
        attach(conn, true);
    }

    public void attach(TcpConnection conn, boolean initializeExtensions) {
        this.conn = conn;
        this.channel = conn == null ? null : conn.channel();
        this.childChannel = null;
        this.sendBuffer = conn == null ? null : conn.sendBuffer();
        this.receiveBuffer = conn == null ? null : conn.receiveBuffer();
        this.timers = conn == null ? null : conn.timers();
        this.attributes.clear();
        loadFromConnection(conn);
        if (initializeExtensions || conn == null) {
            initInlineTcpState();
        }
    }

    private void initInlineTcpState() {
        tos = 0;
        ipv4TcpChallengeTimestamp = 0L;
        ipv4TcpChallengeCount = 0;
        icskAckRetry = 0;
        icskAckLastSegSize = 0;
        icskAckLrcvtimeMs = 0L;
        timestampEnabled = false;
        recentTimestamp = 0;
        tsRecentStamp = 0;
        quickAckCount = 0;
        ackTimeoutMs = TcpConstants.DELAYED_ACK_MS;
        pingpongCount = 0;
        lastRecvTimeMs = 0L;
        lastSendTimeMs = 0L;
        srttUs = 0L;
        rttvarUs = 0L;
        // R2.3: rtoBackoffShift / retransStamp 默认值 0/0L 由 Sender 字段声明初始化
        undoRetrans = 0;
        rcvBuf = TcpConstants.TCP_DEFAULT_RCV_BUF;
        windowClamp = TcpConstants.TCP_DEFAULT_RCV_BUF;
        rcvSsthresh = TcpConstants.TCP_DEFAULT_RCV_BUF;
        mssCache = 0;
        pmtuCookie = 0;
        tcpHeaderLen = 0;
        dstMtu = 0;
        // R2.3: sackedOut / lostOut 默认 0(Sender 字段初值)
        rackMstamp = 0L;
        rackRttUs = 0L;
        rackReoWndSteps = 1;
        rackReoWndPersist = 0;
        rackDsackSeen = false;
        delivered = 0;
        rackLastDelivered = 0;
        rackAckPriorDelivered = 0;
        rttMinFilter.reset(0, TcpConstants.TCP_MIN_RTT_NO_SAMPLE);
        linger2 = (int) TcpConstants.FIN_WAIT_2_TIMEOUT_MS;
        probeBackoffShift = 0;
        probesOut = 0;
        probesTstampMs = 0L;
        userTimeoutMs = 0L;
        keepaliveTimeMs = TcpConstants.TCP_KEEPALIVE_TIME_MS;
        keepaliveIntvlMs = TcpConstants.TCP_KEEPALIVE_INTVL_MS;
        keepaliveProbes = TcpConstants.TCP_KEEPALIVE_PROBES;
        keepaliveEnabled = false;
        // R2.3: cwnd / ssthresh 初值由 Sender 字段声明初始化 (TCP_INIT_CWND / Integer.MAX_VALUE)
        // R2.3: sndCwndStampMs / sndCwndUsed / isCwndLimited 默认值由 Sender 字段声明初始化
        dupacks = 0;
        caIncrCounter = 0;
        congestionState = CongestionState.OPEN;
        highSeq = 0;
        // R2.3: tlpHighSeq 默认值 0 由 Sender 字段声明初始化
        dsackStart = 0;
        dsackEnd = 0;
        // R2.3: priorCwnd / priorSsthresh 默认 0 由 Sender 字段声明初始化
        undoMarker = 0;
        frtoHighmark = 0;
        frtoCounter = 0;
    }

    private void loadFromConnection(TcpConnection conn) {
        if (conn == null) {
            return;
        }
        if (channel == null) {
            channel = conn.channel();
        }
        if (sendBuffer == null) {
            sendBuffer = conn.sendBuffer();
        }
        if (receiveBuffer == null) {
            receiveBuffer = conn.receiveBuffer();
        }
        if (timers == null) {
            timers = conn.timers();
        }
        this.state = conn.state();
        // R2.3: sndUna / sndNxt / writeSeq 已在 Sender(dead code path)
        // R3.2: rcvNxt 已在 Receiver(dead code path)
        // R2.3: sndWnd / maxWindow / sndWl1 / sndSml 已在 Sender(dead code path)
        // R3.2: rcvWnd 已在 Receiver(dead code path)
        // R3.2: rcvWup 已在 Receiver(dead code path)
        this.rcvMss = conn.rcvMss();
        this.mss = conn.mss();
        this.sndWscale = conn.sndWscale();
        this.rcvWscale = conn.rcvWscale();
        this.bytesAcked = conn.bytesAcked();
        // R2.3: packetsOut 已在 Sender(dead code path)
        this.skShutdown = conn.skShutdown();
        this.ackPending = conn.ackPending();
        this.skErr = conn.skErr();
        this.lastOowAckTimeMs = conn.lastOowAckTimeMs();
        this.tos = conn.tos();
        this.ipv4TcpChallengeTimestamp = conn.ipv4TcpChallengeTimestamp();
        this.ipv4TcpChallengeCount = conn.ipv4TcpChallengeCount();
        this.icskAckRetry = conn.icskAckRetry();
        this.icskAckLastSegSize = conn.icskAckLastSegSize();
        this.icskAckLrcvtimeMs = conn.icskAckLrcvtimeMs();
        this.timestampEnabled = conn.timestampEnabled();
        this.recentTimestamp = conn.recentTimestamp();
        this.tsRecentStamp = conn.tsRecentStamp();
        this.quickAckCount = conn.quickAckCount();
        this.ackTimeoutMs = conn.ackTimeoutMs();
        this.pingpongCount = conn.pingpongCount();
        this.lastRecvTimeMs = conn.lastRecvTimeMs();
        this.lastSendTimeMs = conn.lastSendTimeMs();
        this.srttUs = conn.srttUs();
        this.rttvarUs = conn.rttvarUs();
        // R2.3: rtoBackoffShift / retransStamp 已在 Sender,此路径为 dead code 保留
        //       (from(conn)/view(conn) 无外部调用者);configure 后由外部重置
        this.undoRetrans = conn.undoRetrans();
        this.mssCache = conn.mssCache();
        this.pmtuCookie = conn.pmtuCookie();
        this.tcpHeaderLen = conn.tcpHeaderLen();
        this.dstMtu = conn.dstMtu();
        // R2.3: sackedOut 已在 Sender(dead code path)
        // R2.3: cwnd / ssthresh 已在 Sender(dead code path)
        // R2.3: sndCwndStampMs / sndCwndUsed / isCwndLimited 已在 Sender(dead code path)
        this.dupacks = conn.dupacks();
        this.caIncrCounter = conn.caIncrCounter();
        this.congestionState = conn.congestionState() == null
                ? CongestionState.OPEN
                : CongestionState.valueOf(conn.congestionState());
        this.highSeq = conn.highSeq();
        // R2.3: tlpHighSeq 已在 Sender(dead code path)
    }

    public Channel channel() {
        return channel;
    }

    public TcpMultiplexer multiplexer() {
        return multiplexer;
    }

    public void multiplexer(TcpMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
    }

    public Sender sender() {
        return sender;
    }

    public void sender(Sender sender) {
        this.sender = sender;
    }

    public Receiver receiver() {
        return receiver;
    }

    public void receiver(Receiver receiver) {
        this.receiver = receiver;
    }

    public Channel childChannel() {
        return childChannel;
    }

    public void childChannel(Channel channel) {
        this.childChannel = channel;
    }

    public ChannelFutureListener childCloseListener() {
        return childCloseListener;
    }

    public void childCloseListener(ChannelFutureListener listener) {
        this.childCloseListener = listener;
    }

    public TcpSockHandler handler() {
        return handler;
    }

    public void handler(TcpSockHandler handler) {
        this.handler = handler;
    }

    public boolean hasHandler() {
        return handler != null;
    }

    public boolean rcvPaused() {
        return receiver.paused();
    }

    public void rcvPaused(boolean v) {
        receiver.paused(v);
    }

    /**
     * 该连接的专属 EventLoop — 对齐 v1 里 {@code childChannel.eventLoop()} 的角色,
     * 由 {@code Tcp4Multiplexer#tcp_v4_syn_recv_sock} 在建立 child sock 时从
     * {@code tcpGroup.next()} 分配。为 {@code null} 时回退到 TUN {@code channel.eventLoop()}
     * (保持 listenSock / 无专属 EL 场景的兼容)。
     *
     * <p>线程模型:一旦绑定,该 sock 的状态机、timer、user channel pipeline 及(可选的)
     * backend channel 都跑在这条 EL 上,入站分发由 {@code tcp_v4_rcv} 负责在 TUN EL →
     * sock EL 之间 {@code execute} 跳转。
     */
    private EventLoop tcpEventLoop;

    public EventLoop eventLoop() {
        if (tcpEventLoop != null) {
            return tcpEventLoop;
        }
        return channel == null ? null : channel.eventLoop();
    }

    public void tcpEventLoop(EventLoop el) {
        this.tcpEventLoop = el;
    }

    public int sndUna() {
        return sender.sndUna();
    }

    public int sndNxt() {
        return sender.sndNxt();
    }

    public void sndNxt(int v) {
        sender.sndNxt(v);
    }

    public void sndUna(int v) {
        sender.sndUna(v);
    }

    public int writeSeq() {
        return sender.writeSeq();
    }

    public void writeSeq(int v) {
        sender.writeSeq(v);
    }

    public int rcvNxt() {
        return receiver.rcvNxt();
    }

    public void rcvNxt(int v) {
        receiver.rcvNxt(v);
    }

    public int sndWnd() {
        return sender.sndWnd();
    }

    public void sndWnd(int v) {
        sender.sndWnd(v);
    }

    public int maxWindow() {
        return sender.maxWindow();
    }

    public void maxWindow(int v) {
        sender.maxWindow(v);
    }

    public int packetsOut() {
        return sender.packetsOut();
    }

    public void packetsOut(int v) {
        sender.packetsOut(v);
    }

    /**
     * 对齐 Linux {@code tcp_packets_in_flight}(include/net/tcp.h):
     * {@code packets_out - sacked_out - lost_out + retrans_out}。v2 暂不维护
     * {@code retransOut} 单独计数,SACK 语义由 {@code sackedOut} 覆盖;{@code lostOut}
     * 由 RACK / NewReno 标记,两者共同从 in_flight 中排除。
     *
     * <p>{@link TcpOutput#cwndTest} 用此值作 cwnd 预算分子,避免 SACKed 段既
     * 占 {@code packetsOut}、又被 cwnd 扣减的双倍计费。
     */
    public int packetsInFlight() {
        return Math.max(0, sender.packetsOut() - sender.sackedOut() - sender.lostOut());
    }

    public int sndWl1() {
        return sender.sndWl1();
    }

    public void sndWl1(int v) {
        sender.sndWl1(v);
    }

    public int sndSml() {
        return sender.sndSml();
    }

    public void sndSml(int v) {
        sender.sndSml(v);
    }

    public int rcvWnd() {
        return receiver.rcvWnd();
    }

    public void rcvWnd(int v) {
        receiver.rcvWnd(v);
    }

    public int rcvWup() {
        return receiver.rcvWup();
    }

    public void rcvWup(int v) {
        receiver.rcvWup(v);
    }

    public int rcvMss() {
        return rcvMss;
    }

    public void rcvMss(int v) {
        this.rcvMss = v;
    }

    public int mss() {
        return mss;
    }

    public void mss(int v) {
        this.mss = v;
    }

    public int sndWscale() {
        return sndWscale;
    }

    public void sndWscale(int v) {
        this.sndWscale = v;
    }

    public int rcvWscale() {
        return rcvWscale;
    }

    public void rcvWscale(int v) {
        this.rcvWscale = v;
    }

    public long bytesAcked() {
        return bytesAcked;
    }

    public void bytesAcked(long v) {
        this.bytesAcked = v;
    }

    public int skShutdown() {
        return skShutdown;
    }

    public void skShutdown(int mask) {
        this.skShutdown = mask;
    }

    public void addShutdown(int how) {
        this.skShutdown |= how;
    }

    public boolean hasShutdown(int how) {
        return (this.skShutdown & how) != 0;
    }

    public int ackPending() {
        return ackPending;
    }

    public void addAckPending(int bits) {
        this.ackPending |= bits;
    }

    public void clearAckPending(int bits) {
        this.ackPending &= ~bits;
    }

    public boolean hasAckPending(int bits) {
        return (this.ackPending & bits) != 0;
    }

    public int skErr() {
        return skErr;
    }

    public void skErr(int err) {
        this.skErr = err;
    }

    public long lastOowAckTimeMs() {
        return lastOowAckTimeMs;
    }

    public void lastOowAckTimeMs(long v) {
        this.lastOowAckTimeMs = v;
    }

    public int tos() {
        return tos;
    }

    public void tos(int v) {
        this.tos = v;
    }

    public long ipv4TcpChallengeTimestamp() {
        return ipv4TcpChallengeTimestamp;
    }

    public void ipv4TcpChallengeTimestamp(long v) {
        this.ipv4TcpChallengeTimestamp = v;
    }

    public int ipv4TcpChallengeCount() {
        return ipv4TcpChallengeCount;
    }

    public void ipv4TcpChallengeCount(int v) {
        this.ipv4TcpChallengeCount = v;
    }

    public int icskAckRetry() {
        return icskAckRetry;
    }

    public void icskAckRetry(int v) {
        this.icskAckRetry = Math.max(v, 0);
    }

    public int icskAckLastSegSize() {
        return icskAckLastSegSize;
    }

    public void icskAckLastSegSize(int v) {
        this.icskAckLastSegSize = Math.max(v, 0);
    }

    public long icskAckLrcvtimeMs() {
        return icskAckLrcvtimeMs;
    }

    public void icskAckLrcvtimeMs(long v) {
        this.icskAckLrcvtimeMs = v;
    }

    public int receiveWindow() {
        return Math.max(0, receiver.rcvWup() + receiver.rcvWnd() - receiver.rcvNxt());
    }

    public int sndUnaUpdate(int ackSeq) {
        int cur = sender.sndUna();
        if (!after(ackSeq, cur)) {
            return 0;
        }
        int delta = ackSeq - cur;
        sender.sndUna(ackSeq);
        bytesAcked += delta;
        return delta;
    }

    public int acknowledgeUpTo(int ackSeq) {
        int delta = sndUnaUpdate(ackSeq);
        if (delta > 0 && sendBuffer != null) {
            sender.decrementPacketsOut(sendBuffer.acknowledgeUpTo(ackSeq));
        }
        return delta;
    }

    public void incrementPacketsOut() {
        sender.incrementPacketsOut();
    }

    public void decrementPacketsOut(int count) {
        sender.decrementPacketsOut(count);
    }

    public TcpSegment tcpSendHead() {
        return sendBuffer == null ? null : sendBuffer.peekWrite();
    }

    public void queueSkb(TcpSegment skb) {
        if (sendBuffer != null) {
            sender.writeSeq(skb.endSeq());
            sendBuffer.enqueue(skb);
        }
    }

    public void cleanRtxQueue(int ackSeq) {
        if (sendBuffer != null) {
            sender.decrementPacketsOut(sendBuffer.acknowledgeUpTo(ackSeq));
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttr(ConnectionKey<T> key) {
        return (T) attributes.get(key);
    }

    public <T> void setAttr(ConnectionKey<T> key, T value) {
        attributes.put(key, value);
    }

    public void removeAttr(ConnectionKey<?> key) {
        attributes.remove(key);
    }

    public TcpConnectionTimers timers() {
        return timers;
    }

    public TcpSendBuffer sendBuffer() {
        return sendBuffer;
    }

    /**
     * 写路径 pending 总量({@code writeQueue + rtxQueue} 应用字节)。供 {@code TcpChannel}
     * writability 水位判定使用。
     */
    public long pendingBytes() {
        return sendBuffer == null ? 0L : sendBuffer.pendingBytes();
    }

    public TcpReceiveBuffer receiveBuffer() {
        return receiveBuffer;
    }

    public void close() {
        if (timers != null) {
            timers.cancelAll();
        }
        if (childChannel != null && childChannel.isOpen()) {
            if (childCloseListener != null) {
                childChannel.closeFuture().removeListener(childCloseListener);
            }
            childChannel.close();
        }
        /*
         * handler 必须在 sendBuffer / receiveBuffer release 之前收到
         * onSocketDestroyed 回调,保证 fireChannelInactive 之后用户 handler 调
         * ctx.channel() 或 ctx.pipeline() 时底层状态仍可观测(仅用于诊断)。
         * 实际 ByteBuf 引用计数由 pipeline 的 release-handler 负责,此处仅
         * 释放缓冲区整体。
         */
        if (handler != null) {
            TcpSockHandler h = handler;
            handler = null;
            try {
                h.onSocketDestroyed();
            } catch (Throwable ignore) {
                // 保护:用户 handler 异常不影响 sock 清理
            }
        }
        if (sendBuffer != null) {
            sendBuffer.releaseAll();
        }
        if (receiveBuffer != null) {
            receiveBuffer.releaseAll();
        }
    }

    public boolean timestampEnabled() {
        return timestampEnabled;
    }

    public void timestampEnabled(boolean v) {
        this.timestampEnabled = v;
    }

    public int recentTimestamp() {
        return recentTimestamp;
    }

    /**
     * 对齐 Linux {@code tcp_paws_check} (include/net/tcp.h:416):
     * <ul>
     *   <li>ts_recent - rcv_tsval &le; {@code TCP_PAWS_WINDOW} 时接受(带 1 tick 回绕容差);</li>
     *   <li>ts_recent 为 0(尚未建立基线)时接受;</li>
     *   <li>{@code ts_recent_stamp} 距今超过 {@code TCP_PAWS_24DAYS} 时接受(陈旧基线覆盖);</li>
     *   <li>否则判定为 PAWS 拒绝。</li>
     * </ul>
     */
    public boolean pawsRejected(int tsval) {
        if (!timestampEnabled) {
            return false;
        }
        if (recentTimestamp == 0) {
            return false;
        }
        int delta = recentTimestamp - tsval;
        if (delta <= com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_PAWS_WINDOW) {
            return false;
        }
        // 24 天陈旧分支:基线过老,允许任意 TSval 覆盖
        if (tsRecentStamp != 0) {
            long ageSec = (long) (TcpMultiplexer.nowSeconds() - tsRecentStamp);
            if (ageSec >= com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_PAWS_24DAYS_SEC) {
                return false;
            }
        }
        return true;
    }

    public int tsRecentStamp() {
        return tsRecentStamp;
    }

    public void tsRecentStamp(int v) {
        tsRecentStamp = v;
    }

    public void updateRecentTimestamp(int tsval) {
        if (timestampEnabled) {
            recentTimestamp = tsval;
            tsRecentStamp = TcpMultiplexer.nowSeconds();
        }
    }

    public int quickAckCount() {
        return quickAckCount;
    }

    public void quickAckCount(int v) {
        quickAckCount = Math.max(v, 0);
    }

    public long ackTimeoutMs() {
        return ackTimeoutMs;
    }

    public void ackTimeoutMs(long v) {
        ackTimeoutMs = Math.max(v, 1L);
    }

    public boolean inQuickAckMode() {
        return quickAckCount > 0 && !inPingpongMode();
    }

    public void enterQuickAckMode(int maxQuickAcks) {
        incrQuickAckCount(maxQuickAcks);
        exitPingpongMode();
        ackTimeoutMs = TcpConstants.TCP_ATO_MIN_MS;
    }

    public void decQuickAckMode() {
        if (quickAckCount > 0) {
            quickAckCount--;
            if (quickAckCount == 0) {
                ackTimeoutMs = TcpConstants.DELAYED_ACK_MS;
            }
        }
    }

    public void enterPingpongMode() {
        pingpongCount = 1;
    }

    public void exitPingpongMode() {
        pingpongCount = 0;
    }

    public boolean inPingpongMode() {
        return pingpongCount >= TcpConstants.TCP_PINGPONG_THRESH;
    }

    public void incPingpongCount() {
        if (pingpongCount < 0xFF) {
            pingpongCount++;
        }
    }

    public void onDataReceived() {
        onDataReceived(0);
    }

    public void onDataReceived(int len) {
        if (len >= rcvMss) {
            rcvMss = Math.min(len, mss);
        }
        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        if (lastRecvTimeMs == 0L) {
            incrQuickAckCount(TcpConstants.TCP_MAX_QUICKACKS);
            ackTimeoutMs = TcpConstants.TCP_ATO_MIN_MS;
        } else {
            long m = now - lastRecvTimeMs;
            if (m <= TcpConstants.TCP_ATO_MIN_MS / 2) {
                ackTimeoutMs = (ackTimeoutMs >> 1) + TcpConstants.TCP_ATO_MIN_MS / 2;
            } else if (m < ackTimeoutMs) {
                ackTimeoutMs = Math.min((ackTimeoutMs >> 1) + m, rtoMs());
            } else if (m > ackTimeoutMs) {
                incrQuickAckCount(TcpConstants.TCP_MAX_QUICKACKS);
            }
        }
        lastRecvTimeMs = now;
    }

    public void onSegmentReceived() {
        lastRecvTimeMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
    }

    public void onDataSent() {
        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        lastSendTimeMs = now;
        if (lastRecvTimeMs != 0 && now - lastRecvTimeMs < ackTimeoutMs) {
            incPingpongCount();
        }
    }

    public void addRttSample(long rttUs) {
        if (rttUs < 0) {
            return;
        }
        if (srttUs == 0) {
            srttUs = rttUs;
            rttvarUs = rttUs / 2;
        } else {
            long diff = Math.abs(srttUs - rttUs);
            rttvarUs = (3 * rttvarUs + diff) / 4;
            srttUs = (7 * srttUs + rttUs) / 8;
        }
        sender.resetBackoff();

        // 对齐 Linux tcp_update_rtt_min(tcp_input.c):每次 RTT 采样喂 Windowed Filter,
        // 窗长 TCP_MIN_RTT_WIN_SEC × 1000(毫秒坐标,与 tcp_jiffies32 同基);
        // 值 clamp 到 int 半区避免极端 rtt 溢出,min 语义下上界裁剪是安全的。
        final int nowJiffiesMs = (int) com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        final int sampleUs = (rttUs > Integer.MAX_VALUE)
                ? Integer.MAX_VALUE - 1
                : (int) rttUs;
        rttMinFilter.update(TcpConstants.TCP_MIN_RTT_WIN_SEC * 1_000, nowJiffiesMs, sampleUs);
    }

    /**
     * 当前窗内 RTT running min(微秒)— 对齐 Linux {@code tcp_min_rtt}。
     * 尚无采样时返回 {@link TcpConstants#TCP_MIN_RTT_NO_SAMPLE}(消费者据此退化到 {@code srtt}
     * 派生值,保持 RACK 在冷启动阶段的保守行为)。
     */
    public int minRttUs() {
        return rttMinFilter.min();
    }

    /**
     * 对齐 Linux {@code tcp_set_rto}(tcp_input.c):先将 {@code base = srtt + 4·rttvar}
     * clamp 到 {@code [RTO_MIN_MS, RTO_MAX_MS]},再按 {@link #rtoBackoffShift}
     * 逐步左移,每一步检测是否触顶 {@code RTO_MAX_MS}。
     *
     * <p>若 {@code srttUs} 为 0(未取样)使用 {@link TcpConstants#RTO_INIT_MS}(1000ms)。
     */
    public long rtoMs() {
        long baseMs;
        if (srttUs <= 0L) {
            baseMs = TcpConstants.RTO_INIT_MS;
        } else {
            long varianceUs = Math.max(4L * rttvarUs, 0L);
            long baseUs = srttUs + varianceUs;
            baseMs = Math.max((baseUs + 999L) / 1_000L, TcpConstants.RTO_MIN_MS);
        }
        long rtoMs = baseMs;
        for (int i = 0; i < sender.backoffShift(); i++) {
            if (rtoMs >= TcpConstants.RTO_MAX_MS) {
                return TcpConstants.RTO_MAX_MS;
            }
            rtoMs <<= 1;
            if (rtoMs < 0L) {
                return TcpConstants.RTO_MAX_MS;
            }
        }
        return Math.min(rtoMs, TcpConstants.RTO_MAX_MS);
    }

    public long srttUs() {
        return srttUs;
    }

    public void backoffRto() {
        sender.backoff();
    }

    public void resetRtoBackoff() {
        sender.resetBackoff();
    }

    /**
     * 首段重传的发送微秒时戳;0 表示当前无未确认的重传。
     * 对应 Linux {@code tp->retrans_stamp}。
     * <p>R2.3 起字段物理位于 {@link Sender};本方法 delegate 保持 API 兼容。
     */
    public long retransStamp() {
        return sender.retransStamp();
    }

    public void retransStamp(long us) {
        sender.retransStamp(us);
    }

    /**
     * 尚未被 ACK 覆盖的重传段计数;对应 Linux {@code tp->undo_retrans}。
     */
    public int undoRetrans() {
        return undoRetrans;
    }

    public void undoRetrans(int v) {
        this.undoRetrans = Math.max(v, 0);
    }

    public void incrUndoRetrans() {
        undoRetrans++;
    }

    public void decrUndoRetrans(int n) {
        if (n <= 0) return;
        undoRetrans = Math.max(undoRetrans - n, 0);
    }

    /** 对应 Linux {@code sk->sk_rcvbuf}。 */
    public int rcvBuf() { return rcvBuf; }
    public void rcvBuf(int v) { this.rcvBuf = Math.max(v, 0); }

    /** 对应 Linux {@code tp->window_clamp}。 */
    public int windowClamp() { return windowClamp; }
    public void windowClamp(int v) { this.windowClamp = Math.max(v, 0); }

    /** 对应 Linux {@code tp->rcv_ssthresh}。 */
    public int rcvSsthresh() { return rcvSsthresh; }
    public void rcvSsthresh(int v) { this.rcvSsthresh = Math.max(v, 0); }

    /** 对应 Linux {@code tp->mss_cache}。 */
    public int mssCache() { return mssCache; }
    public void mssCache(int v) { this.mssCache = Math.max(v, 0); }

    /** 对应 Linux {@code icsk->icsk_pmtu_cookie}。 */
    public int pmtuCookie() { return pmtuCookie; }
    public void pmtuCookie(int v) { this.pmtuCookie = Math.max(v, 0); }

    /** 对应 Linux {@code tp->tcp_header_len}。 */
    public int tcpHeaderLen() { return tcpHeaderLen; }
    public void tcpHeaderLen(int v) { this.tcpHeaderLen = Math.max(v, 0); }

    /**
     * 对应 Linux {@code dst_mtu(sk->sk_dst_cache)}。外部 ICMP PTB / PMTU 发现路径写入此值;
     * 写入后下一次 {@link TcpOutput#currentMss} 会检测到与 {@link #pmtuCookie} 不一致,
     * 触发 {@link TcpOutput#syncMss} 重新推导 {@link #mssCache}。
     */
    public int dstMtu() { return dstMtu; }
    public void dstMtu(int v) { this.dstMtu = Math.max(v, 0); }

    /** 对应 Linux {@code tp->sacked_out}。 */
    public int sackedOut() { return sender.sackedOut(); }
    public void sackedOut(int v) { sender.sackedOut(v); }
    public void incrSackedOut() { sender.incrementSackedOut(); }
    public void decrSackedOut(int n) { sender.decrementSackedOut(n); }

    /** 对应 Linux {@code tp->lost_out}。 */
    public int lostOut() { return sender.lostOut(); }
    public void incrLostOut() { sender.incrementLostOut(); }
    public void decrLostOut(int n) { sender.decrementLostOut(n); }

    /**
     * 登记下一次 ACK 要通告的 DSACK 块 {@code [start, end)} — 对齐 Linux
     * {@code tcp_dsack_set}。已存在待发 DSACK 时以最新值覆盖(v2 当前只通告一次,
     * 不做 Linux 的 DSACK extend/coalesce)。
     */
    public void setDsack(int start, int end) {
        if (start == end) return;
        this.dsackStart = start;
        this.dsackEnd = end;
    }

    /** 当前是否有待通告的 DSACK 块。 */
    public boolean hasPendingDsack() {
        return dsackStart != dsackEnd;
    }

    public int dsackStart() { return dsackStart; }
    public int dsackEnd() { return dsackEnd; }

    /**
     * 读取并清零当前待发的 DSACK。返回 {@code true} 表示本次读取到有效块 —
     * 对齐 Linux {@code __tcp_send_ack} 调用 {@code tcp_write_xmit} 后
     * {@code tp->duplicate_sack[0]} 清零的语义。
     */
    public boolean consumeDsack(int[] dst) {
        if (dsackStart == dsackEnd) {
            return false;
        }
        dst[0] = dsackStart;
        dst[1] = dsackEnd;
        dsackStart = 0;
        dsackEnd = 0;
        return true;
    }

    public int priorCwnd() { return sender.priorCwnd(); }
    public int priorSsthresh() { return sender.priorSsthresh(); }
    public int undoMarker() { return undoMarker; }
    public int frtoHighmark() { return frtoHighmark; }
    public int frtoCounter() { return frtoCounter; }

    /**
     * 清除 F-RTO 武装状态 — 对应 Linux {@code tp->frto = 0}。
     * 在 {@link #tcpUndoCwndReduction}、伪 RTO 判定命中后或自然退出 CA_Loss 时调用,
     * 避免 F-RTO 高水位影响下一轮 RTO 判定。
     */
    public void clearFrto() {
        this.frtoHighmark = 0;
        this.frtoCounter = 0;
    }

    /**
     * 对齐 Linux {@code tcp_init_undo}:进入 Recovery/Loss 前,把当前
     * {@code cwnd / ssthresh / snd_una} 快照到 {@code prior_cwnd / prior_ssthresh
     * / undo_marker},为后续 {@code tcp_try_undo_*} 提供回滚基线。
     *
     * <p>同时把 {@code undo_retrans / retrans_stamp} 清零 — 本 epoch 的
     * 计数/打戳由 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput#retransmitSkb}
     * 在首次重传时写入,避免上一 epoch 的残留污染 DSACK-driven undo 判定
     * (Linux 在 {@code __tcp_retransmit_skb} 自增 {@code undo_retrans} 前并不
     * 显式清零;v2 因上一 epoch 的自然退出路径不再保留 {@code tcp_clean_rtx_queue}
     * 兜底,统一在此处重置)。
     */
    public void tcpInitUndo() {
        int c = sender.cwnd();
        int s = sender.ssthresh();
        sender.priorCwnd(c);
        sender.priorSsthresh((s == Integer.MAX_VALUE) ? 0 : s);
        undoMarker = sender.sndUna();
        undoRetrans = 0;
        sender.retransStamp(0L);
    }

    /**
     * 对齐 Linux {@code tcp_undo_cwnd_reduction} (tcp_input.c):
     * 将 {@code cwnd / ssthresh} 回滚到 {@link #tcpInitUndo} 记录的快照(取 max
     * 防止恢复期已自然增长的 cwnd 被压回);清空 {@code undo_marker /
     * retrans_stamp / undo_retrans},结束本轮 undo 机会。
     *
     * @param unmarkLoss 为 {@code true} 时同时清空 {@code lost_out}
     *                   (tcp_try_undo_loss 专用);{@code false} 保留 LOST 位
     */
    public void tcpUndoCwndReduction(boolean unmarkLoss) {
        int pc = sender.priorCwnd();
        if (pc > 0) {
            sender.cwnd(Math.max(sender.cwnd(), pc));
        }
        int ps = sender.priorSsthresh();
        if (ps > 0) {
            sender.ssthresh(Math.max(sender.ssthresh(), ps));
        }
        undoMarker = 0;
        sender.retransStamp(0L);
        undoRetrans = 0;
        clearFrto();
        if (unmarkLoss) {
            sender.lostOut(0);
        }
    }

    /**
     * 对齐 Linux {@code tcp_try_undo_recovery} (tcp_input.c:2672) 的 TSECR-based
     * 最小闭环:
     * <ol>
     *   <li>当前 CA_Recovery 且 {@code undo_marker / retrans_stamp} 均有效(有一次
     *       undo 机会);</li>
     *   <li>本 ACK 的 TSECR(对端 echo 回来的我们之前发送段的 TSval)早于
     *       {@code retrans_stamp} — 说明被 ACK 的段是原始段而非重传副本,重传属伪触发;</li>
     *   <li>命中则调 {@link #tcpUndoCwndReduction} 回滚 cwnd/ssthresh,并把
     *       CA 状态直接迁到 CA_Open(跳过自然 {@code cwnd = ssthresh} deflate)。</li>
     * </ol>
     * {@code !tp->undo_retrans} 兜底路径由 {@link #tcpTryUndoDsack()} 单独承担:
     * {@code TcpAck.sacktagWriteQueue} 在 DSACK Case 1/2 时按 Linux
     * {@code tcp_check_dsack} 守卫递减 {@code undoRetrans},清零后在
     * {@code TcpAck.tcpAck} 尾部命中 DSACK undo 分支。
     *
     * @param tsecr 本次 ACK 的 TSECR(32-bit ms);传 {@code -1} 表示无时戳选项,
     *              无法判定,返回 {@code false}
     * @return {@code true} 若本次 ACK 触发了 undo 并已迁至 CA_Open
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_try_undo_recovery</a>
     */
    public boolean tcpTryUndoRecovery(int tsecr) {
        if (congestionState != CongestionState.RECOVERY) return false;
        if (undoMarker == 0 || sender.retransStamp() == 0L) return false;
        if (tsecr == -1) return false;
        final int retransStampMs = (int) (sender.retransStamp() / 1000L);
        if (!com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before(tsecr, retransStampMs)) {
            return false;
        }
        tcpUndoCwndReduction(false);
        congestionState = CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /**
     * 对齐 Linux {@code tcp_try_undo_loss} (tcp_input.c:2722) 的 TSECR-based 伪 RTO 闭环:
     * <ol>
     *   <li>当前 CA_Loss 且 {@code undo_marker / retrans_stamp} 均有效(RTO 进入 Loss 时
     *       {@link #tcpInitUndo} 已完成快照);</li>
     *   <li>本 ACK 的 TSECR 早于 {@code retrans_stamp} — 说明被 ACK 的段是原始段而非
     *       RTO 触发的重传副本,判定为伪 RTO;</li>
     *   <li>命中则 {@link #tcpUndoCwndReduction} 回滚 cwnd/ssthresh 至 pre-RTO 快照,
     *       CA 状态直接迁到 CA_Open,重置 {@code dupacks / caIncrCounter}。</li>
     * </ol>
     * Linux 的 {@code !tp->undo_retrans} 兜底(所有被 DSACK 抵消的重传)在 v2 中
     * 由 {@link #tcpTryUndoDsack()} 承担,CA_Loss 场景下与本方法共享入口分支 —
     * 若 TSECR 未命中,{@code TcpAck.tcpAck} 会继续检查 DSACK undo 路径。
     *
     * @param tsecr 本次 ACK 的 TSECR(32-bit ms);传 {@code -1} 表示无时戳选项
     * @return {@code true} 若本次 ACK 触发了 Loss undo 并已迁至 CA_Open
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_try_undo_loss</a>
     */
    public boolean tcpTryUndoLoss(int tsecr) {
        if (congestionState != CongestionState.LOSS) return false;
        if (undoMarker == 0 || sender.retransStamp() == 0L) return false;
        if (tsecr == -1) return false;
        final int retransStampMs = (int) (sender.retransStamp() / 1000L);
        if (!com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before(tsecr, retransStampMs)) {
            return false;
        }
        tcpUndoCwndReduction(false);
        congestionState = CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /**
     * 对齐 Linux {@code tcp_process_loss} 中 F-RTO 分支(RFC 5682 / tcp_input.c
     * 中 {@code tp->frto} 消费路径)—— 基于 ACK 累计覆盖 {@code snd_nxt_at_rto}
     * 判定伪 RTO,独立于 TSECR 通道:
     * <ol>
     *   <li>当前 CA_Loss 且 {@link #frtoCounter} {@code == 1}(本轮 RTO 已武装);</li>
     *   <li>本 ACK 的 {@code sndUna} 已追平或越过 {@link #frtoHighmark}
     *       (= RTO 瞬间的 {@code snd_nxt})— 意味着 RTO 之前在飞的**所有**原始段
     *       都已被累计 ACK 吸收,而 CA_Loss 期间 {@code cwnd = 1} 仅发出重传副本,
     *       没有任何新数据能推高 {@code snd_una} 到该水位;因此 RTO 属伪触发。</li>
     *   <li>命中则 {@link #tcpUndoCwndReduction}{@code (true)} 回滚
     *       {@code cwnd/ssthresh} 并清 {@code lost_out}(RTO 的 LOST 标记需一并清除),
     *       迁 CA_Open,重置 {@code dupacks / caIncrCounter},{@link #clearFrto}
     *       由 {@code tcpUndoCwndReduction} 顺带完成。</li>
     * </ol>
     * 与 {@link #tcpTryUndoLoss(int)}(TSECR)互补:TSECR 通道精确但依赖时戳选项
     * 启用 + ACK 对端正确 echo;F-RTO 通道不依赖时戳,但要求 RTO 之前存在在飞数据
     * 以形成有效参照线({@link #onTimeoutByCc} 武装时已守卫 {@code after(sndNxt,
     * undoMarker)})。Linux 中两条分支由 {@code tcp_process_loss} 内部择一触发,
     * 对应 v2 在 {@code TcpAck.tcpAck} 尾部的 {@code FULLUNDO → LOSSUNDO →
     * SPURIOUSRTOS → DSACKUNDO} else-if 链中按顺序判定,命中后互斥。
     *
     * @return {@code true} 若本次 ACK 触发了 F-RTO undo 并已迁至 CA_Open
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_process_loss</a>
     */
    public boolean tcpProcessFrto() {
        if (congestionState != CongestionState.LOSS) return false;
        if (frtoCounter == 0) return false;
        if (undoMarker == 0) return false;
        if (com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before(sender.sndUna(), frtoHighmark)) {
            return false;
        }
        tcpUndoCwndReduction(true);
        congestionState = CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /**
     * 对齐 Linux {@code tcp_try_undo_dsack} (tcp_input.c) 的 DSACK-driven undo:
     * <ol>
     *   <li>当前处于 CA_Recovery 或 CA_Loss 且 {@code undo_marker} 已在
     *       {@link #tcpInitUndo} 中建立;</li>
     *   <li>本 epoch 至少发出过一次重传({@code retransStamp != 0L} 间接表明
     *       {@code __tcp_retransmit_skb} 命中过,对应 Linux 的 {@code tp->undo_retrans}
     *       曾被自增);</li>
     *   <li>在 {@code TcpAck.sacktagWriteQueue} 经 DSACK Case 1/2 递减后
     *       {@code undoRetrans == 0} — 等价于 Linux {@code !tp->undo_retrans},
     *       说明所有重传副本都已被 DSACK 抵消,判定为伪触发。</li>
     * </ol>
     * 命中则 {@link #tcpUndoCwndReduction}(false) 回滚 {@code cwnd/ssthresh},
     * 直接迁 CA_Open 并清零 {@code dupacks / caIncrCounter}。
     *
     * <p>与 {@link #tcpTryUndoRecovery(int)} / {@link #tcpTryUndoLoss(int)} 互斥:
     * {@code TcpAck.tcpAck} 按 FULL → LOSS → DSACK 顺序 else-if 判断,对齐
     * Linux {@code tcp_fastretrans_alert} 中 {@code tcp_packet_delayed} 优先
     * 于 {@code !tp->undo_retrans} 兜底的流程。
     *
     * @return {@code true} 若本次 ACK 触发了 DSACK undo 并已迁至 CA_Open
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_try_undo_dsack</a>
     */
    public boolean tcpTryUndoDsack() {
        if (congestionState != CongestionState.RECOVERY && congestionState != CongestionState.LOSS) {
            return false;
        }
        if (undoMarker == 0 || sender.retransStamp() == 0L) return false;
        if (undoRetrans != 0) return false;
        tcpUndoCwndReduction(false);
        congestionState = CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /**
     * 当前连续 dupack 计数 — 对应 Linux {@code tp->dup_acks}(近似)。RFC 3042
     * Limited Transmit 在 {@code TcpOutput.cwndTest} 读取该值,在
     * {@code dupacks ∈ [1, 2]} 且 {@link #isCaOpen()} 时放宽 cwnd 预算。
     */
    public int dupacks() { return dupacks; }

    /**
     * 当前 CA 状态是否为 {@code CA_Open} — {@code CongestionState} 枚举对 ng 包外不可见,
     * 暴露此谓词供 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput}
     * 判定 RFC 3042 Limited Transmit 是否生效。
     */
    public boolean isCaOpen() { return congestionState == CongestionState.OPEN; }

    /** RACK 最新 SACKed 段 {@code sentTimeUs};单调更新。 */
    public long rackMstamp() { return rackMstamp; }
    public void updateRack(long sentTimeUs, long rttUs) {
        if (sentTimeUs > rackMstamp) {
            rackMstamp = sentTimeUs;
            rackRttUs = Math.max(rttUs, 0L);
        }
    }
    public long rackRttUs() { return rackRttUs; }

    /** RACK {@code reo_wnd_steps} — 参与 {@code reo_wnd = base × steps} 的动态缩放。 */
    public int rackReoWndSteps() { return rackReoWndSteps; }
    /** 设置 {@code tp->rack.dsack_seen};由 {@code tcp_sacktag_write_queue} DSACK 首块命中时调用。 */
    public void setRackDsackSeen(boolean seen) { this.rackDsackSeen = seen; }
    /** 当前是否已观察到待消费 DSACK 事件。 */
    public boolean rackDsackSeen() { return rackDsackSeen; }

    /** 读取 {@code tp->delivered}(段投递计数)。 */
    public int  delivered()                    { return delivered; }
    /** {@code tp->delivered++};在累计 ACK 或新 SACK 标记释放段时调用。 */
    public void incrDelivered(int n)           { this.delivered += Math.max(n, 0); }
    /** {@code rs->prior_delivered} scratchpad:本 ACK 内"已投递段" tx.delivered 的最大值。 */
    public int  rackAckPriorDelivered()        { return rackAckPriorDelivered; }
    /** {@code tcpAck} 入口复位 / {@code clean_rtx / sacktag} 路径用单调最大值刷新。 */
    public void updateRackAckPriorDelivered(int txDelivered) {
        if (after(txDelivered, rackAckPriorDelivered)) {
            rackAckPriorDelivered = txDelivered;
        }
    }
    /** {@code tcpAck} 入口清零 scratchpad。 */
    public void clearRackAckPriorDelivered()   { this.rackAckPriorDelivered = 0; }
    /** {@code tp->rack.last_delivered} — 上次 reo_wnd 步进时的 {@code tp->delivered} 快照。 */
    public int  rackLastDelivered()            { return rackLastDelivered; }

    /**
     * 对齐 Linux {@code tcp_rack_update_reo_wnd} (tcp_recovery.c):
     * <ul>
     *   <li>S-3 1-RTT 门控:若本 ACK 里 {@code rs->prior_delivered} 早于
     *       {@code tp->rack.last_delivered},说明被确认的段是在上次 reo_wnd 调整之前
     *       发出的,属同一 RTT 的旧 DSACK → 清 {@code dsack_seen} 不再步进;</li>
     *   <li>若 DSACK 事件有效 → {@code reo_wnd_steps++}(封顶 0xFF),同步更新
     *       {@code rack.last_delivered = tp->delivered},并把
     *       {@code reo_wnd_persist} 复位为
     *       {@link TcpConstants#TCP_RACK_RECOVERY_THRESH}(16);</li>
     *   <li>否则 {@code reo_wnd_persist} 递减;到 0 时衰减 {@code reo_wnd_steps}
     *       回 1,恢复默认 reo_wnd。</li>
     * </ul>
     * 对应 MIB 计数 {@code TCPDSACKRECV} 已在 {@code tcp_sacktag_write_queue} 计入。
     *
     * @param priorDelivered 本 ACK 所确认段的 {@code TCP_SKB_CB->tx.delivered} 最大值
     *                       (对应 Linux {@code rs->prior_delivered});{@code 0} 表示
     *                       本 ACK 未确认任何已打戳的段(例如纯窗口更新),直接返回。
     */
    public void tcpRackUpdateReoWnd(int priorDelivered) {
        if (priorDelivered == 0) {
            return;
        }
        // S-3: Disregard DSACK if a rtt has not passed since we adjusted reo_wnd
        if (rackDsackSeen
                && before(priorDelivered, rackLastDelivered)) {
            rackDsackSeen = false;
        }
        if (rackDsackSeen) {
            rackReoWndSteps   = Math.min(rackReoWndSteps + 1, 0xFF);
            rackDsackSeen     = false;
            rackLastDelivered = delivered;
            rackReoWndPersist = TcpConstants.TCP_RACK_RECOVERY_THRESH;
        } else if (rackReoWndPersist <= 0) {
            rackReoWndSteps = 1;
        } else {
            rackReoWndPersist--;
        }
    }

    public int linger2() {
        return linger2;
    }

    public void linger2(int linger2) {
        this.linger2 = linger2;
    }

    public void incrQuickAckCount(int maxQuickAcks) {
        int quickacks = receiver.rcvWnd() / Math.max(rcvMss << 1, 1);
        if (quickacks == 0) {
            quickacks = 2;
        }
        quickAckCount = Math.max(quickAckCount, Math.min(quickacks, maxQuickAcks));
    }

    public int tcpFinTimeMs() {
        int finTimeout = linger2 != 0 ? linger2 : (int) TcpConstants.FIN_WAIT_2_TIMEOUT_MS;
        long rto = rtoMs();
        long minTimeout = (rto << 2) - (rto >> 1);
        if (finTimeout < minTimeout) {
            finTimeout = (int) minTimeout;
        }
        return finTimeout;
    }

    public int probeBackoffShift() {
        return probeBackoffShift;
    }

    public void probeBackoffShift(int probeBackoffShift) {
        this.probeBackoffShift = Math.max(probeBackoffShift, 0);
    }

    public void incProbeBackoff() {
        if (probeBackoffShift < 31) {
            probeBackoffShift++;
        }
    }

    public int probesOut() {
        return probesOut;
    }

    public void probesOut(int probesOut) {
        this.probesOut = Math.max(probesOut, 0);
    }

    public long probesTstampMs() {
        return probesTstampMs;
    }

    public void probesTstampMs(long probesTstampMs) {
        this.probesTstampMs = Math.max(probesTstampMs, 0L);
    }

    public long userTimeoutMs() {
        return userTimeoutMs;
    }

    public void userTimeoutMs(long userTimeoutMs) {
        this.userTimeoutMs = Math.max(userTimeoutMs, 0L);
    }

    public long keepaliveTimeMs() {
        return keepaliveTimeMs;
    }

    public long keepaliveIntvlMs() {
        return keepaliveIntvlMs;
    }

    public int keepaliveProbes() {
        return keepaliveProbes;
    }

    public boolean keepaliveEnabled() {
        return keepaliveEnabled;
    }

    public void keepaliveEnabled(boolean keepaliveEnabled) {
        this.keepaliveEnabled = keepaliveEnabled;
    }

    public long keepaliveElapsedMs() {
        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        long lastActivity = lastRecvTimeMs != 0L ? lastRecvTimeMs : lastSendTimeMs;
        if (lastActivity == 0L) {
            return 0L;
        }
        return Math.max(now - lastActivity, 0L);
    }

    public long tcpRtoMaxMs() {
        return TcpConstants.RTO_MAX_MS;
    }

    public long tcpProbe0BaseMs() {
        return Math.max(rtoMs(), TcpConstants.RTO_MIN_MS);
    }

    public long tcpProbe0WhenMs(long maxWhenMs) {
        int backoff = Math.min(9, probeBackoffShift);
        long when = tcpProbe0BaseMs() << backoff;
        return Math.min(when, maxWhenMs);
    }

    public long tcpClampProbe0ToUserTimeout(long whenMs) {
        if (userTimeoutMs == 0L || probesTstampMs == 0L) {
            return whenMs;
        }
        long elapsed = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32() - probesTstampMs;
        if (elapsed < 0L) {
            elapsed = 0L;
        }
        long remaining = Math.max(userTimeoutMs - elapsed, TcpConstants.RTO_MIN_MS);
        return Math.min(remaining, whenMs);
    }

    public void resetProbeState() {
        probeBackoffShift = 0;
        probesOut = 0;
        probesTstampMs = 0L;
    }

    public Runnable probeTimerAction() {
        return probeTimerAction;
    }

    public void probeTimerAction(Runnable probeTimerAction) {
        this.probeTimerAction = probeTimerAction == null ? () -> {} : probeTimerAction;
    }

    public Runnable keepaliveTimerAction() {
        return keepaliveTimerAction;
    }

    public void keepaliveTimerAction(Runnable keepaliveTimerAction) {
        this.keepaliveTimerAction = keepaliveTimerAction == null ? () -> {} : keepaliveTimerAction;
    }

    public void onAckedByCc(int newlyAcked, boolean advanced) {
        if (!advanced) {
            if (++dupacks == 3 && congestionState == CongestionState.OPEN) {
                // 对齐 Linux tcp_init_undo:进 Recovery 前快照 cwnd/ssthresh/snd_una,
                // 为后续 tcp_try_undo_recovery 提供回滚基线。
                tcpInitUndo();
                int newSs = Math.max(sender.cwnd() / 2, 2);
                sender.ssthresh(newSs);
                sender.cwnd(newSs + 3);
                highSeq = sender.sndNxt();
                sender.tlpHighSeq(0);
                congestionState = CongestionState.RECOVERY;
                caIncrCounter = 0;
                // 对齐 Linux tcp_enter_recovery → markHeadLost:NewReno 无 SACK
                // 信息驱动 LOST 标记,进入 Fast Retransmit 前先把队首段记为 LOST,
                // 这样 retransmitSkb 的 LOST 优先路径能与 RACK 场景保持一致。
                TcpAck.markHeadLost(this, 1);
                this.multiplexer.retransmitter().retransmit(this);
            } else if (congestionState == CongestionState.RECOVERY) {
                sender.incrementCwnd();
            }
            return;
        }

        if (congestionState == CongestionState.RECOVERY && after(sender.sndUna(), highSeq)) {
            sender.cwnd(sender.ssthresh());
            congestionState = CongestionState.OPEN;
            caIncrCounter = 0;
        } else if (congestionState == CongestionState.LOSS) {
            congestionState = CongestionState.OPEN;
            caIncrCounter = 0;
            // 自然退出 CA_Loss(非 F-RTO / TSECR undo 路径)时一并清 F-RTO 武装,
            // 避免 high_mark 悬挂影响下一轮 RTO 判定。
            clearFrto();
        }

        dupacks = 0;
        int c = sender.cwnd();
        int s = sender.ssthresh();
        if (c < s) {
            sender.cwnd(c + newlyAcked);
        } else {
            caIncrCounter += newlyAcked;
            if (caIncrCounter >= c) {
                sender.incrementCwnd();
                caIncrCounter = 0;
            }
        }
    }

    public void onTimeoutByCc() {
        // 对齐 Linux tcp_enter_loss → tcp_init_undo:RTO 前快照 cwnd/ssthresh/snd_una,
        // F4-2 tcp_try_undo_loss 将据此判定伪 RTO 并回滚。
        tcpInitUndo();
        // 对齐 Linux tcp_enter_loss 中 F-RTO 武装条件(RFC 5682):
        // 有 undo 机会(undoMarker 已建立)且 RTO 瞬间仍有在飞数据(sndNxt > undoMarker),
        // 才把 snd_nxt 快照为 frto_high_mark,等待后续 ACK 判定伪 RTO;否则 RTO 无可用
        // 参照线(单段队列 / 连接刚建立 / snd.nxt == snd.una)直接跳过 F-RTO。
        if (undoMarker != 0 && after(sender.sndNxt(), undoMarker)) {
            frtoHighmark = sender.sndNxt();
            frtoCounter = 1;
        } else {
            clearFrto();
        }
        sender.ssthresh(Math.max(sender.cwnd() / 2, 2));
        sender.cwnd(1);
        dupacks = 0;
        caIncrCounter = 0;
        sender.tlpHighSeq(0);
        congestionState = CongestionState.LOSS;
    }

    public int tlpHighSeq() {
        return sender.tlpHighSeq();
    }

    public void tlpHighSeq(int tlpHighSeq) {
        sender.tlpHighSeq(tlpHighSeq);
    }

    public int cwnd() {
        return sender.cwnd();
    }

    public void cwnd(int cwnd) {
        sender.cwnd(cwnd);
    }

    public int ssthresh() {
        return sender.ssthresh();
    }

    public void ssthresh(int ssthresh) {
        sender.ssthresh(ssthresh);
    }

    public CongestionState congestionState() {
        return congestionState;
    }

    public long sndCwndStampMs() {
        return sender.sndCwndStampMs();
    }

    public void sndCwndStampMs(long v) {
        sender.sndCwndStampMs(v);
    }

    public int sndCwndUsed() {
        return sender.sndCwndUsed();
    }

    public void sndCwndUsed(int v) {
        sender.sndCwndUsed(v);
    }

    public boolean isCwndLimited() {
        return sender.isCwndLimited();
    }

    public void isCwndLimited(boolean v) {
        sender.isCwndLimited(v);
    }

    /**
     * 空闲期后的 cwnd 回退 — 对齐 Linux {@code tcp_slow_start_after_idle_check}
     * (tcp_output.c)。在 {@code tcp_write_xmit} 入口处调用:
     * <ul>
     *   <li>sysctl 关闭 / 尚有 {@code packets_out} / CC 接管拥塞控制 时直接返回;</li>
     *   <li>{@code delta = now - lsndtime > RTO} 时触发 {@link #tcpCwndRestart(long)}
     *       把 cwnd 按 {@code RTO} 为步长对半衰减到 {@code TCP_INIT_CWND} 上限。</li>
     * </ul>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L150">tcp_cwnd_restart</a>
     */
    public void tcpSlowStartAfterIdleCheck() {
        if (!SysctlOptions.ipv4_sysctl_tcp_slow_start_after_idle) {
            return;
        }
        if (sender.packetsOut() != 0) {
            return;
        }
        // lsndtime 尚未建立(从未发送过)— Linux 同样跳过。
        if (lastSendTimeMs == 0L) {
            return;
        }
        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        long delta = now - lastSendTimeMs;
        long rtoMs = rtoMs();
        if (delta > rtoMs) {
            tcpCwndRestart(delta, rtoMs);
        }
    }

    /**
     * cwnd 空闲回退的实际实现 — 对齐 Linux {@code tcp_cwnd_restart}。
     * <ul>
     *   <li>{@code restart_cwnd = min(TCP_INIT_CWND, cwnd)};</li>
     *   <li>{@code ssthresh} 先记录当前值的保守上界(Linux {@code tcp_current_ssthresh}
     *       在 CA_Open 时返回 {@code max(ssthresh, 3*cwnd/4 + 1)})以便未来慢启动阶段
     *       有明确收敛目标;</li>
     *   <li>按每 {@code RTO} 为步长把 {@code cwnd} 向右移 1,直至降至
     *       {@code restart_cwnd} 上限;</li>
     *   <li>重置 {@code snd_cwnd_used} 并刷新 {@code snd_cwnd_stamp},清除上一窗口的
     *       application-limited 记账。</li>
     * </ul>
     */
    private void tcpCwndRestart(long delta, long rtoMs) {
        int restartCwnd = TcpConstants.TCP_INIT_CWND;
        int curCwnd = sender.cwnd();
        // 近似 Linux tcp_current_ssthresh():CA_Open 下 max(ssthresh, 3*cwnd/4 + 1)
        if (congestionState == CongestionState.OPEN) {
            int conservative = (curCwnd * 3 / 4) + 1;
            if (sender.ssthresh() < conservative) {
                sender.ssthresh(conservative);
            }
        }
        restartCwnd = Math.min(restartCwnd, curCwnd);
        long remain = delta;
        while ((remain -= rtoMs) > 0 && curCwnd > restartCwnd) {
            curCwnd >>= 1;
        }
        sender.cwnd(Math.max(curCwnd, restartCwnd));
        sender.sndCwndStampMs(com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32());
        sender.sndCwndUsed(0);
    }

    /**
     * 发送本轮结束后的 cwnd 可用性确认 — 对齐 Linux {@code tcp_cwnd_validate}
     * (tcp_output.c:2359)。
     * <ul>
     *   <li>{@code is_cwnd_limited = true} → 置位 {@code tp->is_cwnd_limited}
     *       并刷新 {@code snd_cwnd_stamp},记录"当前窗口已填满";</li>
     *   <li>{@code is_cwnd_limited = false} → 若此前 {@code is_cwnd_limited} 置位且
     *       距上次 stamp 已超过一个 RTO,说明应用长期欠载,调用
     *       {@link #tcpCwndApplicationLimited()} 把 cwnd 向下收敛到
     *       {@code (cwnd + snd_cwnd_used) / 2}。</li>
     * </ul>
     *
     * <p>Linux 里还会 maintain {@code tp->max_packets_out}(给 BBR/PRR 用),v2 暂不追踪。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2359">tcp_cwnd_validate</a>
     */
    public void tcpCwndValidate(boolean isCwndLimitedFlag) {
        if (isCwndLimitedFlag) {
            sender.isCwndLimited(true);
            sender.sndCwndStampMs(com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32());
        } else {
            // 应用欠载:若上一窗口 cwnd 曾经受限,等待 1 RTO 后做收敛。
            if (sender.isCwndLimited()) {
                long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
                long elapsed = now - sender.sndCwndStampMs();
                if (elapsed >= rtoMs()) {
                    tcpCwndApplicationLimited();
                }
            }
        }
    }

    /**
     * application-limited 场景下的 cwnd 收敛 — 对齐 Linux
     * {@code tcp_cwnd_application_limited}。Linux 有附加 {@code SOCK_NOSPACE}
     * 检查(buffer 非满才回收),v2 无对应字段,按 CA_Open 即回收。
     */
    private void tcpCwndApplicationLimited() {
        if (congestionState == CongestionState.OPEN) {
            int initWin = TcpConstants.TCP_INIT_CWND;
            int winUsed = Math.max(sender.sndCwndUsed(), initWin);
            int c = sender.cwnd();
            if (winUsed < c) {
                // ssthresh = tcp_current_ssthresh() — CA_Open 时为 max(ssthresh, 3*cwnd/4+1)
                int conservative = (c * 3 / 4) + 1;
                if (sender.ssthresh() < conservative) {
                    sender.ssthresh(conservative);
                }
                sender.cwnd((c + winUsed) >> 1);
            }
            sender.sndCwndUsed(0);
        }
        sender.sndCwndStampMs(com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32());
        sender.isCwndLimited(false);
    }

    /**
     * 单次发送后更新 {@code snd_cwnd_used} 高水位 — 对齐 Linux
     * {@code tcp_event_new_data_sent} 内的 {@code snd_cwnd_used} 追踪逻辑。
     * 必须在 {@code packetsOut++} 之后调用。
     */
    public void onDataSentUpdateCwndUsed() {
        int po = sender.packetsOut();
        if (sender.sndCwndUsed() < po) {
            sender.sndCwndUsed(po);
            sender.sndCwndStampMs(com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32());
        }
    }

    @Override
    public TcpConnectionState state() {
        return state;
    }

    @Override
    public void state(TcpConnectionState state) {
        this.state = state;
    }
}
