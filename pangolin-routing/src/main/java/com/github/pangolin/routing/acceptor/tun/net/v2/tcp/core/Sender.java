package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import io.netty.buffer.ByteBuf;

/**
 * 发送侧关注点的聚合对象,对齐 gVisor netstack 的 {@code sender}
 * (pkg/tcpip/transport/tcp/snd.go)。每条 {@link TcpSock} 对应一个 {@code Sender},
 * 由 {@link TcpMultiplexer#configure(TcpSock)} 创建并挂入 {@link TcpSock#sender()}。
 *
 * <p><b>职责</b>:cwnd / ssthresh / nagle / push / fragment / 重传调度 / RTO 状态;
 * 不含接收侧(OFO / reassembly / rcvWnd),后者由 {@link Receiver}。
 *
 * <p><b>当前实现形态</b>:Sender 是 facade — 方法 delegate 到
 * {@link TcpOutput} / {@link TcpRetransmitter} / {@link TcpMultiplexer} 的底层实现,
 * 发送侧 mutable state(writeSeq / sndUna / cwnd / ssthresh / packetsOut / sendBuffer 等)
 * 仍存在 TcpSock。未来若要物理下沉,替换本类方法实现即可,调用方零感知。
 *
 * <p><b>线程模型</b>:所有方法必须在 {@code sock.eventLoop()} 上调用。
 *
 * <p><b>使用示例</b>:
 * <pre>
 *   sock.sender().sendmsg(buf, true);       // 应用层写入(MSS 切片内部处理)
 *   sock.sender().sendFin();                // 半关
 *   sock.sender().retransmit();             // 重传 RTX 队首段
 *   sock.sender().backoff();                // RTO 指数退避
 *   long rto = sock.sender().rtoMs();       // 读当前 RTO
 * </pre>
 */
public final class Sender {

    private final TcpSock sock;

    /**
     * RTO 指数退避 shift(R2.3 物理迁移到 Sender)。Mirrors Linux
     * {@code inet_csk(sk)->icsk_backoff}。默认 0,每次 RTO timer 触发递增(上限 6)。
     */
    private int rtoBackoffShift;
    /**
     * 首段重传发送时戳(us),0 表示当前无未确认的重传。Mirrors Linux
     * {@code tp->retrans_stamp}。R2.3 物理迁移到 Sender。
     */
    private long retransStamp;
    /**
     * TLP 探测段的 highSeq,0 表示未在 TLP 阶段。Mirrors Linux
     * {@code tp->tlp_high_seq}。R2.3 物理迁移到 Sender。
     */
    private int tlpHighSeq;

    Sender(TcpSock sock) {
        this.sock = sock;
    }

    public TcpSock sock() {
        return sock;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 发送行为 API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 应用层 payload 入发送队列。Mirrors Linux {@code tcp_sendmsg}(net/ipv4/tcp.c):
     * 跨 EL 跳转 + MSS 切片 + 可选 push。调用方把 ByteBuf 的引用权转给本方法,
     * 内部负责 release(无论成功失败)。
     *
     * <p>{@code TcpChannel.doWrite} 和 {@code TcpPassthroughInitializer} 反向适配器
     * 都走这里,是发送数据的统一入口。
     */
    public void sendmsg(ByteBuf data, boolean flush) {
        sock.multiplexer().sendmsg(sock, data, flush);
    }

    /** Mirrors Linux {@code tcp_current_mss} (tcp_output.c). */
    public int currentMss() {
        return sock.multiplexer().output().currentMss(sock);
    }

    /** Mirrors Linux {@code tcp_push_pending_frames} (tcp_output.c) — push 本轮待发段。 */
    public void pushPending() {
        sock.multiplexer().pushPendingFrames(sock);
    }

    /** Mirrors Linux {@code tcp_send_fin} — 本端主动发 FIN。 */
    public void sendFin() {
        sock.multiplexer().output().sendFin(sock);
    }

    /** Mirrors Linux {@code tcp_send_active_reset} — 本端主动发 RST。 */
    public void sendReset() {
        sock.multiplexer().output().sendReset(sock);
    }

    /** Mirrors Linux {@code __tcp_send_ack} — 立即发一个纯 ACK。 */
    public void sendAck() {
        sock.multiplexer().output().sendAck(sock);
    }

    /** Mirrors Linux {@code tcp_retransmit_skb} — 重传 RTX 队首段。 */
    public void retransmit() {
        sock.multiplexer().retransmitter().retransmit(sock);
    }

    /** Mirrors Linux {@code tcp_rearm_rto} — ACK 推进后重置 / 取消 RTO 定时器。 */
    public void rearmRto() {
        sock.multiplexer().retransmitter().rearmRto(sock);
    }

    /** Mirrors Linux {@code tcp_event_retransmit_timer} — RTO 到期入口。 */
    public void onRtoTimeout() {
        sock.multiplexer().retransmitter().onTimeout(sock);
    }

    /** Mirrors Linux {@code tcp_send_challenge_ack} — RFC 5961 挑战 ACK。 */
    public void sendChallengeAck(boolean accecnReflector) {
        sock.multiplexer().output().sendChallengeAck(sock, accecnReflector);
    }

    /** Mirrors Linux {@code tcp_sync_mss} — MSS 刷新(PMTU/ICMP 驱动)。 */
    public int syncMss(int pmtu) {
        return sock.multiplexer().output().syncMss(sock, pmtu);
    }

    /** Mirrors Linux {@code tcp_retransmit_skb} — 重传 RTX 队首段(不经 RTO timer)。 */
    public void retransmitSkb() {
        sock.multiplexer().output().retransmitSkb(sock);
    }

    /** Mirrors Linux {@code tcp_send_loss_probe} — TLP 探测包。 */
    public void sendLossProbe() {
        sock.multiplexer().output().sendLossProbe(sock);
    }

    /** Mirrors Linux {@code tcp_rearm_rto} variant — 按当前 RTO 装重传定时器。 */
    public void scheduleRetransmit() {
        sock.multiplexer().retransmitter().scheduleRetransmit(sock);
    }

    /** Mirrors Linux {@code tcp_schedule_loss_probe} — 装 TLP 定时器。 */
    public void scheduleLossProbe(long delayMs) {
        sock.multiplexer().retransmitter().scheduleLossProbe(sock, delayMs);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 发送侧状态访问器 — 当前 delegate 到 TcpSock 的物理字段
    // ═══════════════════════════════════════════════════════════════════════

    /** RTO 指数退避 +1(上限 6)。Mirrors Linux {@code tcp_retransmit_timer} 中的 backoff 递增。 */
    public void backoff() {
        if (rtoBackoffShift < 6) {
            rtoBackoffShift++;
        }
    }

    /** 复位 RTO backoff。Mirrors Linux {@code icsk->icsk_backoff = 0}(ACK 推进后)。 */
    public void resetBackoff() {
        rtoBackoffShift = 0;
    }

    /** 当前 RTO backoff shift。Mirrors Linux {@code icsk->icsk_backoff}。 */
    public int backoffShift() {
        return rtoBackoffShift;
    }

    /** 直接设置 backoff shift(只在 attach 等恢复路径用)。 */
    public void backoffShift(int v) {
        this.rtoBackoffShift = Math.max(0, v);
    }

    /** 当前 RTO (ms),按 srtt/rttvar + backoff。Mirrors Linux {@code inet_csk(sk)->icsk_rto}。 */
    public long rtoMs() {
        return sock.rtoMs();
    }

    /** 首段重传发送时戳(us);0 表示当前无未确认的重传。Mirrors Linux {@code tp->retrans_stamp}。 */
    public long retransStamp() {
        return retransStamp;
    }

    public void retransStamp(long us) {
        this.retransStamp = us;
    }

    /** TLP 探测段的 highSeq。Mirrors Linux {@code tp->tlp_high_seq}。 */
    public int tlpHighSeq() {
        return tlpHighSeq;
    }

    public void tlpHighSeq(int seq) {
        this.tlpHighSeq = seq;
    }
}
