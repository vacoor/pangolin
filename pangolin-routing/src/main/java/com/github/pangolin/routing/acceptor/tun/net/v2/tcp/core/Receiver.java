package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

/**
 * 接收侧关注点的聚合对象,对齐 gVisor netstack 的 {@code receiver}
 * (pkg/tcpip/transport/tcp/rcv.go)。每条 {@link TcpSock} 对应一个 {@code Receiver},
 * 由 {@link SegmentDispatcher#configure(TcpSock)} 创建并挂入 {@link TcpSock#receiver()}。
 *
 * <p><b>职责</b>:
 * <ul>
 *   <li>接收侧状态字段(R3.2 物理下沉):rcvNxt / rcvWnd / rcvWup / rcvPaused /
 *       ackPending / quickAckCount / ackTimeoutMs / pingpongCount 等</li>
 *   <li>ACK 调度(R4.2b-4c 从 Dispatcher 迁入):{@link #ackSndCheck} /
 *       {@link #sendDelayedAck} / {@link #delackTimer}</li>
 *   <li>入站数据 FSM(R4.2b-4f 从 Dispatcher 迁入):{@link #handleDataQueue} /
 *       {@link #finIncoming} / {@link #outOfWindow}(+ 私有 queueAndOut / handleOfo)</li>
 *   <li>初始化(R4.2b-i):{@link #initializeRcvMss}</li>
 * </ul>
 *
 * <p>不含发送侧(cwnd / retransmit / RTO),后者由 {@link Sender}。
 *
 * <p><b>线程模型</b>:所有方法必须在 {@code sock.eventLoop()} 上调用。
 *
 * <p><b>使用示例</b>:
 * <pre>
 *   int next = sock.receiver().rcvNxt();              // 下一个期望序号
 *   sock.receiver().rcvWnd(0);                        // zero-window advertise
 *   sock.receiver().ackSndCheck();                    // 触发 ACK 调度
 *   sock.receiver().handleDataQueue(ctx, pkt);        // 数据段入队
 * </pre>
 */
public final class Receiver {

    private final TcpSock sock;

    /** 下一个期望到达的序号(R3.2 物理迁到 Receiver)。Mirrors Linux {@code tp->rcv_nxt}。 */
    private int rcvNxt;
    /** 当前通告接收窗口(字节)(R3.2 物理迁到 Receiver)。Mirrors Linux {@code tp->rcv_wnd}。 */
    private int rcvWnd;
    /** 上一次通告 window 时的 rcvNxt 快照(R3.2)。Mirrors Linux {@code tp->rcv_wup}。 */
    private int rcvWup;
    /** 背压标志(R3.2);true 时栈暂停向 handler 交付数据。 */
    private boolean rcvPaused;
    /** ACK 调度位图(R3.2 续)。ACK_SCHED / ACK_TIMER / ACK_NOW 的位或集合。 */
    private int ackPending;
    /** quickack 模式剩余配额。Mirrors Linux {@code icsk->icsk_ack.quick}。 */
    private int quickAckCount;
    /** 延迟 ACK 超时。Mirrors Linux {@code icsk->icsk_ack.ato}。 */
    private long ackTimeoutMs = TcpConstants.DELAYED_ACK_MS;
    /** Pingpong 检测计数器。Mirrors Linux {@code icsk->icsk_ack.pingpong}。 */
    private int pingpongCount;
    /** 接收缓冲字节上限。Mirrors Linux {@code sk->sk_rcvbuf}。 */
    private int rcvBuf = TcpConstants.TCP_DEFAULT_RCV_BUF;
    /** 接收窗口硬上限(选项协商后的 window clamp)。Mirrors Linux {@code tp->window_clamp}。 */
    private int windowClamp = TcpConstants.TCP_DEFAULT_RCV_BUF;
    /** 接收侧的拥塞阈值(缓冲 slow start 阈值)。Mirrors Linux {@code tp->rcv_ssthresh}。 */
    private int rcvSsthresh = TcpConstants.TCP_DEFAULT_RCV_BUF;
    /** 下一次 ACK 要通告的 DSACK 块 [start,end)。Mirrors Linux {@code tp->duplicate_sack[0]}。 */
    private int dsackStart;
    private int dsackEnd;
    /** 上次收到对端包的时戳(毫秒 jiffies)。Mirrors Linux {@code tp->lrcv_time}。 */
    private long lastRecvTimeMs;

    Receiver(TcpSock sock) {
        this.sock = sock;
    }

    public TcpSock sock() {
        return sock;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 接收侧状态访问器 + 行为入口
    // ═══════════════════════════════════════════════════════════════════════

    /** 下一个期望到达的序号。Mirrors Linux {@code tp->rcv_nxt}。 */
    public int rcvNxt() {
        return rcvNxt;
    }

    public void rcvNxt(int v) {
        this.rcvNxt = v;
    }

    /** 当前通告接收窗口(字节)。Mirrors Linux {@code tp->rcv_wnd}。 */
    public int rcvWnd() {
        return rcvWnd;
    }

    public void rcvWnd(int v) {
        this.rcvWnd = v;
    }

    /** 上一次通告 window 时的 rcvNxt 快照。Mirrors Linux {@code tp->rcv_wup}。 */
    public int rcvWup() {
        return rcvWup;
    }

    public void rcvWup(int v) {
        this.rcvWup = v;
    }

    /** 背压标志:true 时栈暂停向 handler 交付数据,窗口会收缩。 */
    public boolean paused() {
        return rcvPaused;
    }

    public void paused(boolean v) {
        this.rcvPaused = v;
    }

    /** 接收缓冲对象(按序已交付 + OFO 暂存)。Mirrors Linux {@code sk->sk_receive_queue}。 */
    public TcpReceiveBuffer buffer() {
        return sock.receiveBuffer();
    }

    /** 当前 receive window(去除已占用字节)。Mirrors Linux {@code tcp_receive_window}。 */
    public int receiveWindow() {
        return sock.receiveWindow();
    }

    /** 进入 quickack 模式并预留 n 次立即 ACK 配额。Mirrors Linux {@code tcp_enter_quickack_mode}。 */
    public void enterQuickAck(int n) {
        sock.enterQuickAckMode(n);
    }

    /** 追加 ACK pending 位(ACK_SCHED / ACK_TIMER / ACK_NOW)。 */
    public void addAckPending(int flags) {
        sock.addAckPending(flags);
    }

    /** 清除 ACK pending 位。 */
    public void clearAckPending(int flags) {
        sock.clearAckPending(flags);
    }

    /** ACK 调度位图。 */
    public int ackPending() {
        return ackPending;
    }

    public void addAckPendingBits(int bits) {
        this.ackPending |= bits;
    }

    public void clearAckPendingBits(int bits) {
        this.ackPending &= ~bits;
    }

    public boolean isAckPending(int bits) {
        return (this.ackPending & bits) != 0;
    }

    /** quickack 配额。 */
    public int quickAckCount() {
        return quickAckCount;
    }

    public void quickAckCount(int v) {
        quickAckCount = Math.max(v, 0);
    }

    public int decrementQuickAckCount() {
        if (quickAckCount > 0) quickAckCount--;
        return quickAckCount;
    }

    /** 延迟 ACK 超时。 */
    public long ackTimeoutMs() {
        return ackTimeoutMs;
    }

    public void ackTimeoutMs(long v) {
        ackTimeoutMs = Math.max(v, 1L);
    }

    /** Pingpong 计数器。 */
    public int pingpongCount() {
        return pingpongCount;
    }

    public void pingpongCount(int v) {
        pingpongCount = Math.max(v, 0);
    }

    /** 接收缓冲字节上限。 */
    public int rcvBuf() { return rcvBuf; }
    public void rcvBuf(int v) { this.rcvBuf = Math.max(v, 0); }

    /** window clamp。 */
    public int windowClamp() { return windowClamp; }
    public void windowClamp(int v) { this.windowClamp = Math.max(v, 0); }

    /** rcvSsthresh。 */
    public int rcvSsthresh() { return rcvSsthresh; }
    public void rcvSsthresh(int v) { this.rcvSsthresh = Math.max(v, 0); }

    /** DSACK 区间 start。 */
    public int dsackStart() { return dsackStart; }
    /** DSACK 区间 end。 */
    public int dsackEnd() { return dsackEnd; }
    /** 设置 DSACK 区间。 */
    public void setDsackRange(int start, int end) {
        this.dsackStart = start;
        this.dsackEnd = end;
    }

    /** 上次收到对端包的时戳。 */
    public long lastRecvTimeMs() {
        return lastRecvTimeMs;
    }

    public void lastRecvTimeMs(long v) {
        this.lastRecvTimeMs = v;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACK 调度行为(R4.2b-4c 从 SegmentDispatcher 迁入)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 入站段处理尾部的 ACK 调度判定 — 对齐 Linux
     * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">__tcp_ack_snd_check</a>。
     * 若无 ACK_SCHED / ACK_NOW 标记则直接返回;否则按 quickack / 大接收窗口 /
     * 强制 ACK 分支立即出 ACK,否则走 delayed-ACK 定时器。
     */
    public void ackSndCheck() {
        if (!sock.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_NOW)) {
            return;
        }
        if ((TcpSequence.after(sock.rcvNxt(), sock.rcvWup() + sock.rcvMss())
                && sock.rcvWnd() >= sock.receiveWindow())
                || sock.inQuickAckMode()
                || sock.hasAckPending(TcpConstants.ACK_NOW)) {
            sock.stack().output().sendAck(sock);
            return;
        }
        sendDelayedAck();
    }

    /**
     * 安排一次延迟 ACK 定时器 — 对齐 Linux {@code tcp_send_delayed_ack}。
     * 进 pingpong 模式时把 ATO 夹到至少 {@code config.delayedAckMs()}。
     */
    public void sendDelayedAck() {
        long ato = Math.max(1L, sock.ackTimeoutMs());
        if (sock.inPingpongMode()) {
            ato = Math.max(ato, sock.stack().config.delayedAckMs());
        }
        sock.addAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(sock, ato, this::delackTimer);
    }

    /**
     * 延迟 ACK 定时器到期回调 — 对齐 Linux {@code tcp_delack_timer}。
     * pingpong 模式下退出 pingpong 并把 ATO 重置到下限;否则指数退避到 RTO 上限。
     */
    public void delackTimer() {
        if (!sock.hasConnection() || !sock.hasAckPending(TcpConstants.ACK_TIMER)) {
            return;
        }
        sock.clearAckPending(TcpConstants.ACK_TIMER);
        if (!sock.hasAckPending(TcpConstants.ACK_SCHED)) {
            return;
        }
        if (!sock.inPingpongMode()) {
            sock.ackTimeoutMs(Math.min(sock.ackTimeoutMs() << 1, sock.rtoMs()));
        } else {
            sock.exitPingpongMode();
            sock.ackTimeoutMs(TcpConstants.TCP_ATO_MIN_MS);
        }
        sock.stack().output().sendAck(sock);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 数据入队 + 窗口判定(R4.2b-4f 从 SegmentDispatcher 迁入)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 初始化接收 MSS 提示 — 对齐 Linux {@code tcp_initialize_rcv_mss}。
     * R4.2b-i:从 {@code SegmentDispatcher.initializeRcvMss} 迁入;
     * 调用方在 3WH 收到最后 ACK 后 {@code sk.rcvMss(sk.receiver().initializeRcvMss())}。
     */
    public int initializeRcvMss() {
        if (!sock.hasConnection()) {
            return TcpConstants.TCP_MSS_DEFAULT;
        }
        int mss = sock.mss();
        int hint = Math.min(mss, sock.rcvWnd() / 2);
        hint = Math.min(hint, TcpConstants.TCP_INIT_CWND * mss);
        return Math.max(hint, TcpConstants.TCP_MSS_DEFAULT);
    }

    /**
     * 段是否在可接受窗口内 — 对齐 Linux {@code tcp_sequence}。
     * R4.2b-4f:从 {@code SegmentDispatcher} 迁入的静态工具。
     */
    public static boolean sequenceAcceptable(TcpSock sk, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = sk.rcvWup();
        int rcvWndEnd = sk.rcvNxt() + sk.receiveWindow();
        if (TcpSequence.before(endSeq, rcvWup)) {
            return false;
        }
        if (TcpSequence.after(endSeq, rcvWndEnd)) {
            return !TcpSequence.after(seq, rcvWndEnd);
        }
        return true;
    }

    /**
     * 对齐 Linux {@code dataQueue} (tcp_input.c:5229) 的七分支判定 —— R4.2b-4f 从
     * {@code SegmentDispatcher} 迁入。
     */
    public int handleDataQueue(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (!sock.hasConnection()) {
            return 0;
        }
        final int seq = pkt.tcpSeq();
        final int endSeq = TcpUtils.determineEndSeq(pkt);
        if (seq == endSeq) {
            return 0;
        }
        if (!sock.state().canReceive()) {
            return 0;
        }

        final int rcvNxt = sock.rcvNxt();
        final int rwnd = sock.receiveWindow();

        if (seq == rcvNxt) {
            if (rwnd == 0) {
                if (pkt.tcpPayloadLength() == 0 && pkt.isFin()) {
                    queueAndOut(ctx, pkt);
                } else {
                    outOfWindow(SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
                }
                return 0;
            }
            queueAndOut(ctx, pkt);
            return 0;
        }

        if (!TcpSequence.after(endSeq, rcvNxt)) {
            outOfWindow(SkbDropReason.SKB_DROP_REASON_TCP_OLD_DATA);
            return 0;
        }

        if (!TcpSequence.before(seq, rcvNxt + rwnd)) {
            outOfWindow(SkbDropReason.SKB_DROP_REASON_TCP_OVERWINDOW);
            return 0;
        }

        if (TcpSequence.before(seq, rcvNxt)) {
            if (rwnd == 0) {
                outOfWindow(SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                queueAndOut(ctx, pkt);
            }
            return 0;
        }

        handleOfo(pkt);
        return 0;
    }

    /**
     * 等价 Linux {@code queue_and_out} (tcp_input.c:5150) — 顺序段入缓冲并推进 rcv_nxt,
     * FIN 到达 rcv_nxt 时触发 {@link #finIncoming}。
     */
    private void queueAndOut(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        final int priorRcvNxt = sock.rcvNxt();
        final int payloadLen  = pkt.tcpPayloadLength();
        final int seq         = pkt.tcpSeq();
        final int endSeq      = TcpUtils.determineEndSeq(pkt);
        final boolean fin     = pkt.isFin();

        if (payloadLen > 0) {
            sock.onDataReceived(payloadLen);
        }

        final ByteBuf payload = pkt.tcpPayloadSlice();
        final ByteBuf segment = payloadLen > 0
                ? payload.retainedSlice()
                : Unpooled.EMPTY_BUFFER;

        final TcpReceiveBuffer.OfferResult r = sock.receiveBuffer()
                .offer(seq, endSeq, priorRcvNxt, segment, fin);
        sock.rcvNxt(r.rcvNxt);
        if (r.hasDsack()) {
            sock.setDsack(r.dsackStart, r.dsackEnd);
            sock.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
            sock.addAckPending(TcpConstants.ACK_NOW);
        }

        // 对齐 Linux tcp_clamp_window:OFO 队列触发 prune 即视为内存压力,
        // 把 rcv_ssthresh 写回到 min(windowClamp, 2*advmss),作为"压力记忆";
        // 后续 tcp_grow_window 会从这个低水位重新爬。注意:必须先 clamp 再 grow,
        // 否则同一段同时触发 prune 又被当成"健康"段去 grow,自相矛盾。
        if (r.ofoPruned) {
            tcpClampWindow();
        }

        // 对齐 Linux tcp_event_data_recv → tcp_grow_window(tcp_input.c):
        // 顺序段到达且 payload 非零(Linux 门槛是 >= 128 字节)时,根据空余预算把
        // rcv_ssthresh 向 windowClamp 增长 2*advmss,让长肥管道的通告窗口能
        // 跟随 BDP 自适应扩张,而不是永远停在 createChild 里设置的初始值。
        if (payloadLen >= 128 && !r.ofoPruned) {
            tcpGrowWindow();
        }

        if (sock.receiveBuffer().isReadable() && !sock.rcvPaused()) {
            deliverToHandler(sock.receiveBuffer().readAll());
        }

        if (r.finDelivered) {
            finIncoming(ctx);
            return;
        }

        if (payloadLen > 0) {
            boolean ofoDrain = r.rcvNxt != priorRcvNxt + payloadLen;
            if (ofoDrain || seq != priorRcvNxt) {
                sock.enterQuickAckMode(TcpConstants.TCP_INIT_CWND);
                sock.addAckPending(TcpConstants.ACK_NOW);
            } else {
                sock.addAckPending(TcpConstants.ACK_SCHED);
            }
        }
    }

    /**
     * 对齐 Linux {@code tcp_fin} 全状态 switch — R4.2b-4f 从 {@code SegmentDispatcher} 迁入。
     * 负责状态迁移 + {@code onPeerFin} 通知 + FIN_WAIT_2 → TIME_WAIT 转接。
     */
    public void finIncoming(ChannelHandlerContext ctx) {
        sock.addShutdown(TcpConstants.RCV_SHUTDOWN);
        final TcpConnectionState prevState = sock.state();
        final TcpOutput output = sock.stack().output();
        switch (prevState) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                sock.state(TcpConnectionState.CLOSE_WAIT);
                sock.enterPingpongMode();
                output.sendAck(sock);
                notifyPeerFin(sock);
                return;
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                output.sendAck(sock);
                return;
            case FIN_WAIT_1:
                output.sendAck(sock);
                sock.state(TcpConnectionState.CLOSING);
                notifyPeerFin(sock);
                return;
            case FIN_WAIT_2:
                output.sendAck(sock);
                notifyPeerFin(sock);
                sock.stack().timeWait(ctx, sock, TcpConnectionState.TIME_WAIT);
                return;
            default:
                return;
        }
    }

    /**
     * 应用读取后窗口清理 —— 对齐 Linux {@code tcp_cleanup_rbuf}
     * (net/ipv4/tcp.c)。
     *
     * <p>v2 当前 inline 投递路径下({@link #queueAndOut} 里 {@code readAll} +
     * {@code addAckPending}),数据到达后立即 drain 并 schedule ACK,新窗口由
     * 后续 ACK 携带,无需主动开窗。但当应用通过 {@code rcvPaused=true} 实施
     * 反压、随后 {@code TcpChannel.doBeginRead} 把 paused 翻为 false 时,如果不
     * 主动通告新窗,对端会停在零窗 / 小窗,等下一次入站段才能借 ACK 捎带新窗
     * (而对端可能连那一次都不发,因为它在等我们开窗)。
     *
     * <p>触发条件(对齐 Linux):
     * <ul>
     *   <li>当前 outstanding 通告窗口 = {@code rcvWup + rcvWnd - rcvNxt}(本端
     *       已通告但对端还没消耗的窗口);</li>
     *   <li>"如果现在 select 一次,新通告窗口 ≥ 2 × 当前 outstanding"——即
     *       窗口至少翻倍才发 ACK,避免每次小幅开窗都触发 ACK 风暴;</li>
     *   <li>{@code 2 × outstanding ≤ windowClamp} —— 还有空间继续翻倍。</li>
     * </ul>
     *
     * <p>调用方:{@code TcpChannel.doBeginRead} 在 {@code rcvPaused: true→false}
     * 转换点调用本方法;若将来 handler 改成异步消费(收到数据后缓存,稍后由
     * 应用线程读取),也应在每次"应用读"完成后调一次。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c">tcp_cleanup_rbuf</a>
     */
    public void tcpCleanupRbuf() {
        if (!sock.hasConnection() || rcvPaused) {
            return;
        }
        // 应用读后,先把接收缓冲里残留的有序段交付出去,让 rmemAlloc 真正下降。
        if (sock.receiveBuffer() != null && sock.receiveBuffer().isReadable()) {
            deliverToHandler(sock.receiveBuffer().readAll());
        }
        // 当前对端可见的剩余窗口(右边沿距当前 rcvNxt,字节单位)
        final int curWin = receiveWindow();
        if (curWin <= 0) {
            // outstanding 已为 0(刚 readAll 把 rcvNxt 推到 rcvWup+rcvWnd 处),
            // 任何 select 都会算出新窗 → 主动 ACK 更新。
            sock.stack().output().sendAck(sock);
            return;
        }
        // 计算本轮 select 会写出的新窗(这一调用会更新 rcvWnd / rcvWup 字段)。
        // 注意单位:selectAdvertisedWindow 返回值是 wire 单位 (window >> wscale),
        // 不能直接和 byte 单位的 curWin 比较;读字段拿 byte 单位的新窗。
        TcpOutput.selectAdvertisedWindow(sock);
        final int probeNewWnd = rcvWnd;     // byte 单位,与 curWin 同口径
        if (2L * curWin <= windowClamp && probeNewWnd >= 2L * curWin) {
            sock.stack().output().sendAck(sock);
        }
    }

    /**
     * 内存压力 / OFO 预算耗尽时的 rcv_ssthresh 收紧 —— 对齐 Linux
     * {@code tcp_clamp_window}(net/ipv4/tcp_input.c)。
     *
     * <p>当 {@code rmem_alloc} 超过 {@code sk_rcvbuf}(在 v2 我们用 OFO 队列
     * 触发 {@link TcpReceiveBuffer#tcpPruneOfoQueue} 这一信号近似"压力事件"),
     * Linux 把 {@code rcv_ssthresh} 压到 {@code min(window_clamp, 2*advmss)},
     * 作为"压力记忆":后续即便压力解除,通告窗口也只能从这个低水位经
     * {@link #tcpGrowWindow} 慢慢爬回去,避免反复打满。
     *
     * <p>同时退出 quick-ACK 模式(Linux:{@code icsk->icsk_ack.quick = 0}),
     * 以减少 ACK 风暴对反压恢复的干扰。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_clamp_window</a>
     */
    private void tcpClampWindow() {
        final int advmss = Math.max(sock.mss(), TcpConstants.TCP_MSS_DEFAULT);
        final int newSsthresh = Math.min(sock.windowClamp(), 2 * advmss);
        if (newSsthresh < rcvSsthresh) {
            rcvSsthresh = newSsthresh;
        }
        // 退出 quick-ACK(Linux: icsk->icsk_ack.quick = 0)。v2 用 quickAckCount 表达。
        quickAckCount = 0;
    }

    /**
     * 接收缓冲 slow-start —— 对齐 Linux {@code tcp_grow_window}
     * (net/ipv4/tcp_input.c)。
     *
     * <p>每当一个"够大"(&ge;128 字节)的顺序段到达,且当前不处于内存压力,
     * 就把 {@code rcv_ssthresh} 按 {@code 2*advmss} 的步长朝
     * {@code min(window_clamp, freeSpace)} 推进。目的是让通告窗口随着
     * 接收端展现出的消费能力自适应扩张,而不是永远停在
     * {@code TcpSock.createChild} 里的初始 {@code rcvWnd} 预算 ——
     * 否则长肥管道(高 BDP)会被通告窗口硬限,达不到 cwnd 允许的吞吐。
     *
     * <p>Linux 的 {@code truesize} 与 skb 开销建模在 v2 下简化:
     * {@code freeSpace} 直接用 {@code rcvBuf - rmemAlloc} 经
     * {@code winFromSpace} 换算,与
     * {@link TcpOutput#selectAdvertisedWindow} 保持相同口径。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_grow_window</a>
     */
    private void tcpGrowWindow() {
        if (SysctlOptions.underMemoryPressure()) {
            return;
        }
        final int advmss = Math.max(sock.mss(), TcpConstants.TCP_MSS_DEFAULT);
        final int rmemAlloc = sock.receiveBuffer() != null
                ? sock.receiveBuffer().rmemAlloc() : 0;
        final int freeSpace = winFromSpace(Math.max(sock.rcvBuf() - rmemAlloc, 0));
        final int space = Math.min(sock.windowClamp(), freeSpace);
        final int room = space - rcvSsthresh;
        if (room <= 0) {
            return;
        }
        final int incr = Math.min(room, 2 * advmss);
        rcvSsthresh = Math.min(rcvSsthresh + incr, sock.windowClamp());
    }

    /**
     * 对齐 Linux {@code tcp_win_from_space}(include/net/tcp.h):
     * {@code win = (space * scaling_ratio) >> TCP_RMEM_TO_WIN_SCALE}。
     * 与 {@link TcpOutput} 里的同名私有 helper 保持一致的计算口径。
     */
    private static int winFromSpace(int space) {
        if (space <= 0) return 0;
        long win = ((long) space * TcpConstants.TCP_DEFAULT_SCALING_RATIO)
                >> TcpConstants.TCP_RMEM_TO_WIN_SCALE_SHIFT;
        return win > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) win;
    }

    private static void notifyPeerFin(TcpSock sk) {
        TcpSockHandler h = sk.handler();
        if (h != null) {
            try {
                h.onPeerFin();
            } catch (Throwable ignore) {
                // 保护:用户 handler 异常不影响状态机推进
            }
        }
    }

    /**
     * 越窗 / 过旧段统一丢弃路径 — 对齐 Linux {@code out_of_window}。
     * R4.2b-4f 从 {@code SegmentDispatcher} 迁入。
     */
    public void outOfWindow(int reason) {
        sock.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        sock.addAckPending(TcpConstants.ACK_SCHED);
        TcpMibStats mib = sock.stack().mib();
        mib.inc(TcpMib.OUTOFWINDOWICMPS);
        mib.incDrop(reason);
    }

    /**
     * 纯乱序段入 OFO 队列(等价 Linux {@code dataQueueOfo})。
     * R4.2b-4f 从 {@code SegmentDispatcher} 迁入。
     */
    private void handleOfo(TcpPacketBuf pkt) {
        final int seq     = pkt.tcpSeq();
        final int endSeq  = TcpUtils.determineEndSeq(pkt);
        final boolean fin = pkt.isFin();

        if (seq == endSeq) {
            return;
        }

        final ByteBuf payload    = pkt.tcpPayloadSlice();
        final int     payloadLen = payload.readableBytes();
        final ByteBuf segment    = payloadLen > 0
                ? payload.retainedSlice()
                : Unpooled.EMPTY_BUFFER;

        TcpReceiveBuffer.OfoResult r = sock.receiveBuffer().offerOfo(seq, endSeq, segment, fin);
        if (r.hasDsack()) {
            sock.setDsack(r.dsackStart, r.dsackEnd);
        }
        // OFO 路径同步处理 prune 信号(对齐 Linux tcp_clamp_window 的触发点)
        if (r.pruned) {
            tcpClampWindow();
        }
        if (!r.queued && !r.hasDsack()) {
            return;
        }

        // 对齐 Linux tcp_data_queue_ofo 末尾:OFO 段成功入队 + 没触发 prune 时,
        // 也调一次 tcp_grow_window —— 否则乱序流(Wi-Fi / 多路径)的接收侧
        // ssthresh 不会随 OFO 流量爬升,grow 只在顺序段路径推进。
        if (r.queued && !r.pruned && payloadLen >= 128) {
            tcpGrowWindow();
        }

        sock.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        sock.addAckPending(TcpConstants.ACK_NOW);
    }

    /** 投递到 user handler;handler 为 null 时防御性 release,避免泄露。 */
    private void deliverToHandler(ByteBuf data) {
        TcpSockHandler handler = sock.handler();
        if (handler == null) {
            data.release();
            return;
        }
        handler.onInboundData(data);
    }
}
