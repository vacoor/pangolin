package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import io.netty.buffer.ByteBuf;

/**
 * 统一 TCP 段控制块 — 对齐 Linux {@code struct sk_buff} + {@code TCP_SKB_CB(skb)}
 * (include/linux/skbuff.h, include/net/tcp.h)。
 *
 * <p>v2 将以下 4 条队列共用同一条 SKB 数据结构,与 Linux 完全一致:
 * <ul>
 *   <li>{@code sk->sk_write_queue} — 已完成序号分配但尚未发送的数据段(见 {@link TcpSendBuffer#writeQueue});</li>
 *   <li>{@code sk->tcp_rtx_queue} — 已发送待 ACK 的重传队列(见 {@link TcpSendBuffer#rtxQueue});</li>
 *   <li>{@code tp->out_of_order_queue} — 乱序接收的 OFO 队列(见 {@link TcpReceiveBuffer#ofoQueue});</li>
 *   <li>{@code sk->sk_receive_queue} — 有序交付队列(v2 合并为 {@link TcpReceiveBuffer#readBuffer})。</li>
 * </ul>
 *
 * <p>TCB 生命周期(mirrors Linux):
 * <ol>
 *   <li>在 {@link TcpConnection#tcp_queue_skb} 内完成 {@code startSeq} 赋值后入 write 队列;</li>
 *   <li>{@code TcpOutput.tcp_transmit_skb} 发送后就地继承,{@link #updateSentTime} 打戳,
 *       晋升至 RTX 队列(无新分配);</li>
 *   <li>接收端 OFO 情况下由 {@link TcpReceiveBuffer#offerOfo} 构建对应 SKB,落入
 *       {@link TcpReceiveBuffer#ofoQueue};</li>
 *   <li>被累计 ACK / SACK 完全覆盖后由 {@link TcpSendBuffer#acknowledgeUpTo} 等路径
 *       {@link #release} 释放,或连接关闭时批量释放。</li>
 * </ol>
 *
 * <p>{@code tcpFlags} 对齐 {@code TCP_SKB_CB(skb)->tcp_flags},承载 FIN / SYN / ACK / PSH 等位;
 * 仅 FIN 与 SYN 占序号空间,其余控制位不影响 {@link #endSeq()}。
 *
 * <p>{@code sacked} 对齐 {@code TCP_SKB_CB(skb)->sacked},承载
 * {@link TcpConstants#TCPCB_SACKED_ACKED} / {@link TcpConstants#TCPCB_SACKED_RETRANS} /
 * {@link TcpConstants#TCPCB_LOST} / {@link TcpConstants#TCPCB_EVER_RETRANS} 等位集。
 */
public final class TcpSkb {

    private final ByteBuf payload;
    private final int     startSeq;
    private final int     dataLen;
    private final byte    tcpFlags;   // TCP_SKB_CB(skb)->tcp_flags
    /**
     * TCP_SKB_CB(skb)->sacked 位集:承载 TCPCB_SACKED_ACKED / TCPCB_SACKED_RETRANS /
     * TCPCB_LOST / TCPCB_EVER_RETRANS 等(对齐 Linux include/net/tcp.h)。
     */
    private       int     sacked;
    private       long    sentTimeUs;   // stamped at transmission time (0 while in write queue / OFO)
    /**
     * 发送(或重传)时 {@code tp->delivered} 的快照 — 对齐 Linux
     * {@code TCP_SKB_CB(skb)->tx.delivered}(tcp_rate_skb_sent)。
     * 在 {@code tcp_clean_rtx_queue} / {@code tcp_sacktag_write_queue} 把该段判为投递
     * 时,用该字段去刷新本 ACK 的 {@code rs->prior_delivered}(取最大值 —— 即本 ACK
     * 里"最晚发出且已被确认"的段对应的 delivered 快照)。
     * <p>RACK {@code reo_wnd_steps} 1-RTT 门控(S-3)以此为坐标:若 {@code prior_delivered
     * < rack.last_delivered},说明本 ACK 所确认的段是在上次 reo_wnd 调整之前发出的,
     * 同一 RTT 内不再重复步进。
     */
    private       int     txDelivered;

    public TcpSkb(ByteBuf payload, int startSeq, int dataLen,
                  byte tcpFlags, long sentTimeUs) {
        this(payload, startSeq, dataLen, tcpFlags, 0, sentTimeUs);
    }

    public TcpSkb(ByteBuf payload, int startSeq, int dataLen,
                  byte tcpFlags, int sacked, long sentTimeUs) {
        this.payload    = payload;
        this.startSeq   = startSeq;
        this.dataLen    = dataLen;
        this.tcpFlags   = tcpFlags;
        this.sacked     = sacked;
        this.sentTimeUs = sentTimeUs;
        this.txDelivered = 0;
    }

    /**
     * Exclusive end sequence number of this segment.
     * Mirrors Linux: {@code TCP_SKB_CB(skb)->end_seq = seq + dataLen + syn + fin}.
     * Only FIN and SYN occupy sequence space; RST/PSH/ACK/URG do not.
     */
    public int endSeq() {
        int ctrl = ((tcpFlags & TcpConstants.TCPHDR_FIN) != 0 ? 1 : 0)
                 + ((tcpFlags & TcpConstants.TCPHDR_SYN) != 0 ? 1 : 0);
        return startSeq + dataLen + ctrl;
    }

    public int     startSeq()        { return startSeq; }
    public int     dataLen()         { return dataLen; }
    /** Raw TCP flag bits stored on this SKB (mirrors {@code TCP_SKB_CB->tcp_flags}). */
    public byte    tcpFlags()        { return tcpFlags; }
    public boolean isFin()           { return (tcpFlags & TcpConstants.TCPHDR_FIN) != 0; }
    public boolean isSyn()           { return (tcpFlags & TcpConstants.TCPHDR_SYN) != 0; }
    public ByteBuf payload()         { return payload; }
    public long    sentTimeUs()      { return sentTimeUs; }

    /** 读取 {@code TCP_SKB_CB->sacked} 位集原值。 */
    public int     sacked()          { return sacked; }
    /** 写回 {@code TCP_SKB_CB->sacked} 位集原值。 */
    public void    sacked(int s)     { this.sacked = s; }

    /** 段是否处于"当前未被 ACK 覆盖的重传"状态 — 对应 {@code TCPCB_SACKED_RETRANS}。 */
    public boolean isRetransmitted() {
        return (sacked & TcpConstants.TCPCB_SACKED_RETRANS) != 0;
    }

    /** 段是否历史上被重传过 — 对应 {@code TCPCB_EVER_RETRANS}。 */
    public boolean isEverRetransmitted() {
        return (sacked & TcpConstants.TCPCB_EVER_RETRANS) != 0;
    }

    /** 段是否被 SACK 块确认 — 对应 {@code TCPCB_SACKED_ACKED}。 */
    public boolean isSackAcked() {
        return (sacked & TcpConstants.TCPCB_SACKED_ACKED) != 0;
    }

    /** 段是否被判定丢失 — 对应 {@code TCPCB_LOST}。 */
    public boolean isLost() {
        return (sacked & TcpConstants.TCPCB_LOST) != 0;
    }

    /**
     * 标记为已重传:置位 {@code TCPCB_SACKED_RETRANS | TCPCB_EVER_RETRANS}。
     * 对齐 Linux {@code __tcp_retransmit_skb} 末尾的 {@code TCP_SKB_CB(skb)->sacked |= ...}。
     */
    public void markRetransmitted() {
        sacked |= TcpConstants.TCPCB_SACKED_RETRANS | TcpConstants.TCPCB_EVER_RETRANS;
    }

    /** 清 {@code TCPCB_SACKED_RETRANS} 位(保留 EVER_RETRANS)— 对应 F-RTO undo 路径。 */
    public void clearSackedRetrans() {
        sacked &= ~TcpConstants.TCPCB_SACKED_RETRANS;
    }

    public void updateSentTime(long us)      { this.sentTimeUs = us; }

    /** 读取 {@code TCP_SKB_CB->tx.delivered} — 发送时 {@code tp->delivered} 的快照。 */
    public int  txDelivered()                { return txDelivered; }
    /** 写入 {@code TCP_SKB_CB->tx.delivered};由 {@code tcp_rate_skb_sent} 等价点调用。 */
    public void txDelivered(int d)           { this.txDelivered = d; }

    public void release() {
        if (payload != null) payload.release();
    }
}
