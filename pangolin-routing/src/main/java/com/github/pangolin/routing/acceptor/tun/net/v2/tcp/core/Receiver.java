package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * 接收侧关注点的聚合对象,对齐 gVisor netstack 的 {@code receiver}
 * (pkg/tcpip/transport/tcp/rcv.go)。每条 {@link TcpSock} 对应一个 {@code Receiver},
 * 由 {@link TcpMultiplexer#configure(TcpSock)} 创建并挂入 {@link TcpSock#receiver()}。
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
}
