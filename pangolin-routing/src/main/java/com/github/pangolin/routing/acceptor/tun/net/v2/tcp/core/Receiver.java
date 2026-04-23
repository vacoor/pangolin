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
 * <p><b>职责</b>:rcvNxt / rcvWnd / rcvWup / 背压 / OFO / ACK 调度;
 * 不含发送侧(cwnd / retransmit / RTO),后者由 {@link Sender}。
 *
 * <p><b>当前实现形态</b>:Receiver 是 facade — 方法 delegate 到 {@link TcpSock} /
 * {@link TcpReceiveBuffer} 的底层实现,接收侧 mutable state
 * (rcvNxt / rcvWnd / rcvWup / rcvPaused / receiveBuffer)仍存在 TcpSock。
 * 未来若要物理下沉,替换本类方法实现即可,调用方零感知。
 *
 * <p><b>线程模型</b>:所有方法必须在 {@code sock.eventLoop()} 上调用。
 *
 * <p><b>使用示例</b>:
 * <pre>
 *   int next = sock.receiver().rcvNxt();              // 下一个期望序号
 *   sock.receiver().rcvWnd(0);                        // zero-window advertise
 *   sock.receiver().paused(true);                     // 背压
 *   sock.receiver().enterQuickAck(MAX_QUICKACKS);     // 进 quickack 模式
 *   sock.receiver().addAckPending(ACK_SCHED);         // 调度 ACK
 *   TcpReceiveBuffer buf = sock.receiver().buffer();  // OFO + in-order 缓冲
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
            sock.multiplexer().output().sendAck(sock);
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
            ato = Math.max(ato, sock.multiplexer().config.delayedAckMs());
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
        sock.multiplexer().output().sendAck(sock);
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
        final TcpOutput output = sock.multiplexer().output();
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
                sock.multiplexer().timeWait(ctx, sock, TcpConnectionState.TIME_WAIT);
                return;
            default:
                return;
        }
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
        TcpMibStats mib = sock.multiplexer().mib();
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
        if (!r.queued && !r.hasDsack()) {
            return;
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
