package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import io.netty.channel.ChannelHandlerContext;

import java.util.HashMap;
import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps.oowRateLimited;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.between;

/**
 * LISTEN 端聚合对象 — 把 LISTEN 状态 sock、半连接队列、syn backlog 阈值以及
 * SYN_RECV 校验从 {@link TcpStack} / {@link SegmentDispatcher} 中独立出来。
 * 对齐 gVisor {@code listenEndpoint} 的职责范围(syn / accept queue),
 * 不含 ESTABLISHED 连接管理。
 *
 * <p><b>职责</b>:
 * <ul>
 *   <li>{@link #listenSock} — 状态为 {@code TCP_LISTEN} 的 sock,作为 SYN 匹配
 *       的 "parent sock"(Linux {@code struct request_sock_queue} 的 owner)</li>
 *   <li>{@link #synRegistry} — 半连接队列 ({@code FourTuple → TcpRequestSock}),
 *       对齐 Linux {@code inet_csk(sk)->icsk_accept_queue.listen_opt} + ehash<br>
 *       <b>并发</b>:读写均在 TUN EL,不需要 ConcurrentHashMap</li>
 *   <li>{@link #maxSynBacklog} — 半连接容量上限,对齐 Linux
 *       {@code inet_csk(sk)->icsk_accept_queue.listen_opt.max_qlen_log}</li>
 *   <li>{@link #checkReq} — SYN_RECV 阶段逐段校验(R4.2c 从 SegmentDispatcher 迁入,
 *       需要 {@link SegmentDispatcher} 参数访问 abstract 路由方法)</li>
 *   <li>{@link #synackRttMeas} — 3WH 完成时首个 RTT 样本采样(Karn's rule)</li>
 * </ul>
 *
 * <p><b>访问路径</b>:{@link TcpStack#listener()} 返回本对象;
 * {@link SegmentDispatcher} 子类调 {@link #checkReq} 时自传 {@code this}。
 *
 * <p><b>线程模型</b>:所有访问必须在 TUN EL 上。
 */
public final class Listener {

    /** 半连接队列容量默认值 — 与 {@link TcpStack#DEFAULT_MAX_SYN_BACKLOG} 一致。 */
    public static final int DEFAULT_MAX_SYN_BACKLOG = 1024;

    final TcpSock listenSock;
    final Map<FourTuple, TcpRequestSock> synRegistry = new HashMap<>();
    final int maxSynBacklog;

    Listener(TcpSock listenSock) {
        this(listenSock, DEFAULT_MAX_SYN_BACKLOG);
    }

    Listener(TcpSock listenSock, int maxSynBacklog) {
        this.listenSock = listenSock;
        this.maxSynBacklog = maxSynBacklog;
    }

    public TcpSock listenSock() {
        return listenSock;
    }

    public Map<FourTuple, TcpRequestSock> synRegistry() {
        return synRegistry;
    }

    public int maxSynBacklog() {
        return maxSynBacklog;
    }

    /** 半连接队列当前大小。 */
    public int synQueueSize() {
        return synRegistry.size();
    }

    /** 半连接队列是否已满(达到 {@link #maxSynBacklog})。 */
    public boolean synQueueFull() {
        return synRegistry.size() >= maxSynBacklog;
    }

    /**
     * 加入半连接队列。对齐 Linux {@code inet_csk_reqsk_queue_hash_add}。
     * 幂等:同一 fourTuple 的重复 SYN 不会覆盖已有 req(首次入队为准)。
     */
    public void addRequest(TcpRequestSock req) {
        synRegistry.putIfAbsent(req.fourTuple(), req);
    }

    /** 查找半连接队列中的 req;未命中返回 {@code null}。 */
    public TcpRequestSock findRequest(FourTuple key) {
        return synRegistry.get(key);
    }

    /** 从半连接队列移除 req;返回是否确实移除。 */
    public boolean removeRequest(TcpRequestSock req) {
        return synRegistry.remove(req.fourTuple(), req);
    }

    /**
     * SYN_RECV 阶段的逐段校验 —— R4.2c 从 {@code SegmentDispatcher} 迁入。
     * 严格对齐 Linux
     * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c">checkReq</a>
     * (net/ipv4/tcp_minisocks.c)。
     *
     * <p>处理顺序(与 Linux 一一对应):
     * <ol>
     *   <li>{@code tcp_parse_options} — 解析入站 TS 选项;</li>
     *   <li>PAWS 判定 — {@code th->rst} 不参与 PAWS;</li>
     *   <li>TSECR 范围校验 — {@code rcv_tsecr} 必须落在 {@code [snt_tsval_first, snt_tsval_last]};</li>
     *   <li>纯 SYN 重传分支 — {@code seq == rcv_isn && flg == SYN && !paws_reject}
     *       + {@code oowRateLimited(LINUX_MIB_TCPACKSKIPPEDSYNRECV)} 后 {@code inet_rtx_syn_ack};</li>
     *   <li>OOW / PAWS 拒绝分支 — 若 {@code paws_reject || !tcp_in_window(...)} 则
     *       {@code req->rsk_ops->send_ack} 回 Challenge ACK(非 RST 且未限流),
     *       PAWSESTABREJECTED 记 MIB;</li>
     *   <li>TS 推进 PAWS 基线 — {@code saw_tstamp && !after(seq, rcv_nxt)} 时更新 {@code ts_recent};</li>
     *   <li>{@code seq == rcv_isn} → 清 SYN 标志(对齐 Linux 对 "retrans SYN with data" 的截断);</li>
     *   <li>RST / SYN 走 embryonic_reset — 发 {@code tcp_v4_send_reset} 后销毁 req;</li>
     *   <li>非 ACK 段静默丢弃;</li>
     *   <li>ACK# 必须 {@code == snd_isn + 1},否则走 embryonic_reset;</li>
     *   <li>{@code syn_recv_sock} 建 child → {@link #synackRttMeas} RTT 取样 → 返回 child。</li>
     * </ol>
     *
     * <p>需要 {@code dispatcher} 参数是因为 checkReq 要调用 IP 版本相关的
     * {@code inet_rtx_syn_ack / send_reset / syn_recv_sock},这些是
     * {@link SegmentDispatcher} 的 abstract 方法。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c">checkReq</a>
     */
    public TcpSock checkReq(ChannelHandlerContext net,
                             TcpPacketBuf pkt,
                             TcpRequestSock req,
                             SegmentDispatcher dispatcher) {
        final TcpHandshaker handshaker = req.request();
        final TcpMibStats mib = dispatcher.mib();

        // (1) tcp_parse_options:仅取 TS 选项(PAWS / TSECR 所需)。
        long[] tsOpt = handshaker.clientTimestamp()
                ? TcpOptionCodec.parseTimestamp(pkt.tcpOptionsSlice())
                : null;
        final boolean sawTstamp = tsOpt != null;
        final int rcvTsval = sawTstamp ? (int) tsOpt[0] : 0;
        final long rcvTsecr = sawTstamp ? tsOpt[1] : 0L;

        // (2) tcp_paws_reject —— th->rst 段不做 PAWS。
        boolean pawsReject = sawTstamp && !pkt.isRst() && handshaker.pawsRejected(rcvTsval);

        // (3) TSECR 范围 —— 对齐 checkReq 中的 snt_tsval_first/last LAND-style 校验。
        if (sawTstamp && handshaker.sntTsvalLast() != 0L
                && !between((int) handshaker.sntTsvalFirst(), (int) rcvTsecr, (int) handshaker.sntTsvalLast())) {
            mib.inc(TcpMib.TSECRREJECTED);
            return null;
        }

        final int seq = pkt.tcpSeq();
        final int endSeq = determineEndSeq(pkt);
        final boolean pureSyn = pkt.isSyn() && !pkt.isAck() && !pkt.isRst() && !pkt.isFin();

        // (4) 纯 SYN 重传
        if (seq == handshaker.rcvIsn() && pureSyn && !pawsReject) {
            if (oowRateLimited(handshaker, pkt)) {
                mib.inc(TcpMib.TCPACKSKIPPEDSYNRECV);
            } else {
                dispatcher.inet_rtx_syn_ack(net, listenSock, req);
                req.num_retrans++;
            }
            return null;
        }

        // (5) OOW / PAWS 拒绝
        final int winEnd = handshaker.rcvNxt() + handshaker.synackWindow();
        final boolean inWindow = !before(endSeq, handshaker.rcvNxt()) && !after(seq, winEnd);
        if (pawsReject || !inWindow) {
            if (!pkt.isRst()) {
                if (oowRateLimited(handshaker, pkt)) {
                    mib.inc(TcpMib.TCPACKSKIPPEDSYNRECV);
                } else {
                    handshaker.sendChallengeAck(net.channel());
                }
            }
            if (pawsReject) {
                mib.inc(TcpMib.PAWSESTABREJECTED);
            }
            return null;
        }

        // (6) PAWS 基线推进
        if (sawTstamp && !after(seq, handshaker.rcvNxt())) {
            handshaker.updateTsRecent(rcvTsval);
        }

        // (7) seq == rcv_isn:截断 SYN 标志
        final boolean hasRst = pkt.isRst();
        final boolean hasSyn = pkt.isSyn() && seq != handshaker.rcvIsn();

        // (8) RST / SYN → embryonic_reset
        if (hasRst || hasSyn) {
            if (!hasRst) {
                dispatcher.send_reset(net, pkt, -1);
            }
            dispatcher.inet_csk_destroy_sock(req);
            return null;
        }

        // (9) 非 ACK 静默丢弃
        if (!pkt.isAck()) {
            return null;
        }

        // (10) ACK# 校验
        if (pkt.tcpAckNum() != handshaker.sndIsn() + 1) {
            dispatcher.send_reset(net, pkt, -1);
            dispatcher.inet_csk_destroy_sock(req);
            return null;
        }

        // (11) syn_recv_sock + synackRttMeas
        TcpSock child = dispatcher.syn_recv_sock(net, listenSock, pkt, req);
        if (child != null) {
            synackRttMeas(child, req);
        }
        return child;
    }

    /**
     * 三次握手完成时用 SYN-ACK 首发到 ACK 的时间差作为首个 RTT 样本。
     * Karn's rule:若 SYN-ACK 曾重传({@code num_retrans > 0}),不取样
     * (无法区分 ACK 确认的是哪一次)。
     *
     * <p>R4.2b-4a:从 {@code SegmentDispatcher} 迁入;静态方法,纯操作
     * {@code child} 与 {@code req},无 listener 实例依赖。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">synackRttMeas</a>
     */
    public static void synackRttMeas(TcpSock child, TcpRequestSock req) {
        if (req.num_retrans > 0) {
            return;
        }
        long sntSynackUs = req.request().synAckSentUs();
        if (sntSynackUs == 0L) {
            return;
        }
        long rttUs = (System.nanoTime() / 1_000L) - sntSynackUs;
        if (rttUs <= 0L) {
            return;
        }
        if (!child.hasConnection()) {
            return;
        }
        TcpConnection conn = child.connection();
        if (conn == null || conn.rttEstimator() == null) {
            return;
        }
        conn.rttEstimator().addSample(conn, rttUs);
    }
}

