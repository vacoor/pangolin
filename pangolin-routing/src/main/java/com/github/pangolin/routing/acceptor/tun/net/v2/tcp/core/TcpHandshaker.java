package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf.TCP_FLAG_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf.TCP_FLAG_RST;
import static com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf.TCP_FLAG_SYN;

public final class TcpHandshaker {

    private static final Logger log = LoggerFactory.getLogger(TcpHandshaker.class);
    private static final int MAX_SYNACK_RETRIES = 5;
    private static final long SYNACK_RTO_INIT_MS = 1_000L;

    private final int rcvIsn;
    private final int rcvNxt;
    private final int clientMss;
    private final int clientWscale;
    private final boolean clientTimestamp;
    private final int clientTsVal;
    private final int clientInitWnd;
    private final byte[] srcAddrBytes;
    private final int srcPort;
    private final byte[] dstAddrBytes;
    private final int dstPort;
    private final int sndIsn;
    private final TcpConfig config;

    private boolean synAckSent = false;
    private int synAckRetries = 0;
    private int synAckSendEpoch = 0;
    private ScheduledFuture<?> synAckTimer = null;
    private Runnable synAckFailureAction = () -> {};
    /**
     * 最近一次 SYN-ACK 的发送时戳(微秒),用于三次握手 ACK 到达时的
     * {@code tcp_synack_rtt_meas} RTT 采样。对应 Linux {@code tcp_rsk(req)->snt_synack}。
     * {@code 0L} 表示尚未发送。
     */
    private long synAckSentUs = 0L;

    /**
     * PAWS 基线 — 对齐 Linux {@code tcp_rsk(req)->ts_recent}(include/net/request_sock.h)。
     * 初值在收到首个合法 SYN 时来自 {@code clientTsVal},后续 {@code tcp_check_req} 阶段
     * 不更新(PAWS 基线推进在 syn_recv_sock 建完 child sock 后才发生)。
     */
    private int tsRecent;
    /** {@code ts_recent} 的秒级时间戳(对齐 Linux {@code tcp_rsk(req)->ts_recent_stamp} 派生)。 */
    private int tsRecentStamp;
    /**
     * 本端发出的第一个 SYN-ACK 携带的 TSval(对齐 Linux {@code tcp_rsk(req)->snt_tsval_first})。
     * 用于 TSECR 校验:要求入站 {@code rcv_tsecr} 必须落在 {@code [snt_tsval_first, snt_tsval_last]}。
     */
    private long sntTsvalFirst;
    /** 最近一次 SYN-ACK 的 TSval(对齐 Linux {@code tcp_rsk(req)->snt_tsval_last})。 */
    private long sntTsvalLast;
    /**
     * SYN_RECV 阶段 OOW ACK / SYN-ACK 重发的限流时戳(对齐 Linux
     * {@code tcp_rsk(req)->last_oow_ack_time},{@code 0L} 表示尚未发送过 OOW 响应)。
     */
    private long lastOowAckTimeMs = 0L;

    TcpHandshaker(TcpPacketBuf synPkt, TcpConfig config) {
        this.config = config;
        this.srcAddrBytes = synPkt.srcAddrBytes();
        this.srcPort = synPkt.tcpSrcPort();
        this.dstAddrBytes = synPkt.dstAddrBytes();
        this.dstPort = synPkt.tcpDstPort();
        this.rcvIsn = synPkt.tcpSeq();
        this.rcvNxt = rcvIsn + 1;
        this.clientInitWnd = synPkt.tcpWindow();

        ByteBuf opts = synPkt.tcpOptionsSlice();
        int parsedMss = TcpOptionCodec.parseMss(opts);
        int parsedWscale = config.windowScalingEnabled() ? TcpOptionCodec.parseWindowScale(opts) : -1;
        long[] parsedTs = config.timestampsEnabled() ? TcpOptionCodec.parseTimestamp(opts) : null;
        this.clientMss = (parsedMss > 0) ? Math.min(parsedMss, config.mss()) : config.mss();
        this.clientWscale = parsedWscale;
        this.clientTimestamp = parsedTs != null;
        this.clientTsVal = parsedTs != null ? (int) parsedTs[0] : 0;
        this.sndIsn = TcpUtils.secureSeq(srcAddrBytes, srcPort, dstAddrBytes, dstPort);
        // 对齐 Linux tcp_openreq_init(tcp_input.c:7010):PAWS 基线从首个合法 SYN 的 TSval 继承。
        this.tsRecent = this.clientTsVal;
        this.tsRecentStamp = this.clientTimestamp ? TcpMultiplexer.nowSeconds() : 0;
    }

    public int rcvIsn() {
        return rcvIsn;
    }

    public int rcvNxt() {
        return rcvNxt;
    }

    public int sndIsn() {
        return sndIsn;
    }

    public boolean synAckSent() {
        return synAckSent;
    }

    /**
     * @return 最近一次 SYN-ACK 的发送微秒时戳;尚未发送时返回 {@code 0L}。
     *         用于 {@code tcp_synack_rtt_meas} RTT 采样(对齐 Linux {@code snt_synack})。
     */
    public long synAckSentUs() {
        return synAckSentUs;
    }

    public int clientMss() {
        return clientMss;
    }

    public int clientWscale() {
        return clientWscale;
    }

    public boolean clientTimestamp() {
        return clientTimestamp;
    }

    public int clientTsVal() {
        return clientTsVal;
    }

    public int clientInitWnd() {
        return clientInitWnd;
    }

    public int serverWscale() {
        return clientWscale >= 0 ? config.windowScale() : 0;
    }

    /** PAWS 基线 TSval(对齐 Linux {@code tcp_rsk(req)->ts_recent})。 */
    public int tsRecent() {
        return tsRecent;
    }

    /**
     * 推进 PAWS 基线 — 对齐 Linux {@code tcp_check_req} 中的
     * {@code WRITE_ONCE(tcp_rsk(req)->ts_recent, tmp_opt.rcv_tsval)}。
     * 调用方:{@link TcpMultiplexer#tcp_check_req} 在 {@code saw_tstamp && !after(seq, rcv_nxt)} 时。
     */
    public void updateTsRecent(int tsval) {
        if (!clientTimestamp) {
            return;
        }
        this.tsRecent = tsval;
        this.tsRecentStamp = TcpMultiplexer.nowSeconds();
    }

    /** {@link #tsRecent} 刷新时的秒级时戳(对齐 Linux {@code tcp_rsk(req)->ts_recent_stamp})。 */
    public int tsRecentStamp() {
        return tsRecentStamp;
    }

    /**
     * PAWS 判定 — 对齐 Linux {@code tcp_paws_check}(include/net/tcp.h),与
     * {@link TcpSock#pawsRejected(int)} 保持同一语义:
     * <ul>
     *   <li>未启用 TS 或 {@code ts_recent=0}(尚未建立基线)时接受;</li>
     *   <li>{@code ts_recent - tsval <= TCP_PAWS_WINDOW} 时接受(1 tick 回绕容差);</li>
     *   <li>{@code ts_recent_stamp} 距今 &ge; 24 天则陈旧覆盖,一律接受;</li>
     *   <li>其余视为 PAWS 拒绝。</li>
     * </ul>
     */
    public boolean pawsRejected(int tsval) {
        if (!clientTimestamp) {
            return false;
        }
        if (tsRecent == 0) {
            return false;
        }
        int delta = tsRecent - tsval;
        if (delta <= TcpConstants.TCP_PAWS_WINDOW) {
            return false;
        }
        if (tsRecentStamp != 0) {
            long ageSec = (long) (TcpMultiplexer.nowSeconds() - tsRecentStamp);
            if (ageSec >= TcpConstants.TCP_PAWS_24DAYS_SEC) {
                return false;
            }
        }
        return true;
    }

    /** 首次 SYN-ACK 的 TSval(对齐 Linux {@code tcp_rsk(req)->snt_tsval_first})。 */
    public long sntTsvalFirst() {
        return sntTsvalFirst;
    }

    /** 最近一次 SYN-ACK 的 TSval(对齐 Linux {@code tcp_rsk(req)->snt_tsval_last})。 */
    public long sntTsvalLast() {
        return sntTsvalLast;
    }

    /** SYN_RECV 阶段 OOW 限流时戳(对齐 Linux {@code tcp_rsk(req)->last_oow_ack_time})。 */
    public long lastOowAckTimeMs() {
        return lastOowAckTimeMs;
    }

    public void lastOowAckTimeMs(long v) {
        this.lastOowAckTimeMs = v;
    }

    /**
     * 本端 SYN-ACK 广告的接收窗(尚未 wscale 解码),对齐 Linux
     * {@code tcp_synack_window(req)}。用于 {@code tcp_check_req} 的 in-window 判定:
     * 落点区间为 {@code [rcv_nxt, rcv_nxt + synackWindow())}。
     */
    public int synackWindow() {
        int wnd = config.initialRcvWnd();
        return Math.min(wnd, TcpConstants.TCP_MAX_WINDOW);
    }

    public void cancelRetransmitTimer() {
        if (synAckTimer != null) {
            synAckTimer.cancel(false);
            synAckTimer = null;
        }
    }

    public void synAckFailureAction(Runnable action) {
        this.synAckFailureAction = action == null ? () -> {} : action;
    }

    public void retransmitSynAck(Channel connChannel) {
        if (!synAckSent) {
            return;
        }
        synAckRetries++;
        sendSynAck(connChannel);
    }

    public void sendSynAckAfterBackendConnected(Channel connChannel) {
        sendSynAck(connChannel);
    }

    public void abort() {
        cancelRetransmitTimer();
    }

    public ChannelFuture sendResetAndAbort(Channel connChannel, TcpPacketBuf pkt) {
        cancelRetransmitTimer();
        return sendRst(connChannel, pkt);
    }

    public TcpSock buildChildSock(Channel netChannel, Channel childChannel, TcpPacketBuf pkt) {
        int negotiatedMss = Math.min(clientMss, config.mss());
        int peerWnd = pkt.tcpWindow();
        if (clientWscale >= 0) {
            peerWnd <<= clientWscale;
        }

        cancelRetransmitTimer();
        log.debug("[TCP] [HANDSHAKE] 3WH complete: mss={}, peerWscale={}", negotiatedMss, clientWscale);

        return TcpSock.createChild(
                netChannel,
                childChannel,
                FourTuple.of(dstAddrBytes, dstPort, srcAddrBytes, srcPort),
                sndIsn,
                sndIsn + 1,
                rcvNxt,
                peerWnd,
                config.initialRcvWnd(),
                negotiatedMss,
                clientWscale >= 0 ? clientWscale : 0,
                clientWscale >= 0 ? config.windowScale() : 0,
                clientTimestamp,
                clientTsVal
        );
    }

    public TcpSock finishHandshake(Channel netChannel, Channel childChannel, TcpPacketBuf pkt) {
        final int seq = pkt.tcpSeq();
        if (pkt.isRst()) {
            if (seq == rcvNxt) {
                abort();
            }
            return null;
        }

        if (seq == rcvIsn && (pkt.tcpFlags() & (TCP_FLAG_RST | TCP_FLAG_SYN | TCP_FLAG_ACK)) == TCP_FLAG_SYN) {
            retransmitSynAck(netChannel);
            return null;
        }

        if (!pkt.isAck()) {
            return null;
        }

        if (pkt.tcpAckNum() != sndIsn + 1) {
            log.warn("[TCP] [HANDSHAKE] ACK number mismatch: expected {}, got {}",
                    Integer.toUnsignedString(sndIsn + 1),
                    Integer.toUnsignedString(pkt.tcpAckNum()));
            sendResetAndAbort(netChannel, pkt);
            return null;
        }

        return buildChildSock(netChannel, childChannel, pkt);
    }

    private void sendSynAck(Channel connChannel) {
        // 对齐 Linux tcp_make_synack(tcp_output.c):TSval 生成点与 snt_tsval_* 的记账点一致。
        final long tsvalNow = clientTimestamp ? ((System.nanoTime() / 1_000_000L) & 0xFFFFFFFFL) : 0L;
        byte[] opts = buildSynAckOptions(tsvalNow);
        int window = config.initialRcvWnd() >> (clientWscale >= 0 ? config.windowScale() : 0);
        if (window > TcpConstants.TCP_MAX_WINDOW) window = TcpConstants.TCP_MAX_WINDOW;

        cancelRetransmitTimer();
        if (clientTimestamp) {
            // 对齐 Linux tcp_rsk(req)->snt_tsval_{first,last}:首发记 first,每次发都刷 last。
            if (!synAckSent) {
                sntTsvalFirst = tsvalNow;
            }
            sntTsvalLast = tsvalNow;
        }
        synAckSent = true;
        synAckSentUs = System.nanoTime() / 1_000L;
        final int epoch = ++synAckSendEpoch;
        TcpOutput.INSTANCE.tcp_send_synack(
                connChannel,
                dstAddrBytes, dstPort, srcAddrBytes, srcPort,
                sndIsn, rcvNxt, window, opts
        ).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("[TCP] [HANDSHAKE] SYN-ACK send failed", f.cause());
                return;
            }
            if (epoch != synAckSendEpoch) {
                return;
            }
            log.debug("[TCP] [HANDSHAKE] SYN-ACK sent (isn={}, retry={})",
                    Integer.toUnsignedString(sndIsn), synAckRetries);
            scheduleRetransmit(connChannel);
        });
    }

    private void scheduleRetransmit(Channel connChannel) {
        if (synAckRetries >= MAX_SYNACK_RETRIES) {
            log.debug("[TCP] [HANDSHAKE] SYN-ACK max retransmits ({}) reached - aborting half-open socket", MAX_SYNACK_RETRIES);
            synAckFailureAction.run();
            return;
        }
        long delayMs = SYNACK_RTO_INIT_MS << Math.min(synAckRetries, 6);
        synAckTimer = connChannel.eventLoop().schedule(() -> {
            synAckRetries++;
            log.debug("[TCP] [HANDSHAKE] SYN-ACK retransmit #{}", synAckRetries);
            sendSynAck(connChannel);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private byte[] buildSynAckOptions(long tsvalNow) {
        ByteBuf tmp = Unpooled.buffer(clientTimestamp ? 24 : 12);
        try {
            int ourMss = config.mss();
            int ourWscale = (clientWscale >= 0) ? config.windowScale() : -1;
            Long tsval = clientTimestamp ? tsvalNow : null;
            Long tsecr = clientTimestamp ? ((long) clientTsVal & 0xFFFFFFFFL) : null;
            TcpOptionCodec.writeSynOptions(tmp, ourMss, ourWscale, tsval, tsecr);
            byte[] result = new byte[tmp.readableBytes()];
            tmp.readBytes(result);
            return result;
        } finally {
            tmp.release();
        }
    }

    /**
     * SYN_RECV 阶段发送 Challenge ACK / OOW ACK — 对齐 Linux
     * {@code req->rsk_ops->send_ack}({@code tcp_request_sock_ipv4_ops::send_ack} →
     * {@code tcp_v4_reqsk_send_ack})。
     *
     * <p>调用方:{@link TcpMultiplexer#tcp_check_req} 中 PAWS / TSECR / OOW 分支。
     */
    public void sendChallengeAck(Channel connChannel) {
        int window = config.initialRcvWnd() >> (clientWscale >= 0 ? config.windowScale() : 0);
        if (window > TcpConstants.TCP_MAX_WINDOW) window = TcpConstants.TCP_MAX_WINDOW;
        TcpOutput.INSTANCE.tcp_send_challenge_ack_handshake(
                connChannel,
                dstAddrBytes, dstPort, srcAddrBytes, srcPort,
                sndIsn + 1, rcvNxt, window);
    }

    private ChannelFuture sendRst(Channel connChannel, TcpPacketBuf pkt) {
        return TcpOutput.INSTANCE.tcp_send_reset_handshake(
                connChannel,
                dstAddrBytes, dstPort, srcAddrBytes, srcPort,
                pkt.tcpAckNum());
    }
}
