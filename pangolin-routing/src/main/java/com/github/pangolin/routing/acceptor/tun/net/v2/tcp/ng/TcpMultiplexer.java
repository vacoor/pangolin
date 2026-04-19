package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpReceiveBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSkb;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReasonConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRetransmitter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshaker;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshakerFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.SysctlOptions;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpTimewaitSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpConnectionTimers;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.SHUTDOWN_MASK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_MSS_DEFAULT;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

@Slf4j
public abstract class TcpMultiplexer {
    public static final int DEFAULT_MAX_SYN_BACKLOG = 1024;

    private enum CongestionState {
        OPEN,
        RECOVERY,
        LOSS
    }

    @FunctionalInterface
    public interface DataConsumer {
        void onData(FourTuple key, ByteBuf data);
    }

    private static final DataConsumer DROP_DATA = (key, data) -> data.release();

    /**
     * 返回墙钟秒数(对应 Linux {@code get_seconds()},用于 {@code ts_recent_stamp})。
     * 溢出语义与 Linux 32 位秒戳一致(2106 年回绕)。
     */
    private static int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    public static final int TCP_STATE_MASK = 0xF;
    public static final int TCP_ACTION_FIN = 1 << TcpConnectionState.TCP_CLOSED.ordinal();
    public static final int[] NEW_STATE = new int[TcpConnectionState.values().length + 1];

    static {
        NEW_STATE[TcpConnectionState.TCP_ESTABLISHED.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.TCP_SYN_SENT.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.TCP_SYN_RECV.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.FIN_WAIT_1.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal();
        NEW_STATE[TcpConnectionState.FIN_WAIT_2.ordinal() + 1] = TcpConnectionState.FIN_WAIT_2.ordinal();
        NEW_STATE[TcpConnectionState.TIME_WAIT.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.TCP_CLOSED.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.CLOSE_WAIT.ordinal() + 1] = TcpConnectionState.LAST_ACK.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.LAST_ACK.ordinal() + 1] = TcpConnectionState.LAST_ACK.ordinal();
        NEW_STATE[TcpConnectionState.TCP_LISTEN.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.CLOSING.ordinal() + 1] = TcpConnectionState.CLOSING.ordinal();
    }

    protected final TcpConfig config;
    protected final TcpHandshakerFactory handshakerFactory;
    protected final DataConsumer dataConsumer;
    protected final Map<FourTuple, tcp_request_sock> synRegistry;
    protected final Map<FourTuple, TcpSock> establishedRegistry;
    /**
     * TIME_WAIT 迷你 bucket 注册表 — 对齐 Linux {@code inet_timewait_sock} 加入的
     * {@code ehash} TW 槽位。键为进入 TIME_WAIT 时的四元组,值为快照后的
     * {@link TcpTimewaitSock};2MSL 到期或收到有效 RST 时由 {@link #inet_twsk_kill} 移除。
     */
    protected final Map<FourTuple, TcpTimewaitSock> timewaitRegistry;
    protected final int maxSynBacklog;
    protected TcpSock listenSock;

    protected TcpMultiplexer(TcpConfig config) {
        this(config, DROP_DATA);
    }

    protected TcpMultiplexer(TcpConfig config, DataConsumer dataConsumer) {
        this.config = config;
        this.handshakerFactory = new TcpHandshakerFactory(config);
        this.dataConsumer = dataConsumer == null ? DROP_DATA : dataConsumer;
        this.synRegistry = new HashMap<>();
        this.establishedRegistry = new HashMap<>();
        this.timewaitRegistry = new HashMap<>();
        this.maxSynBacklog = DEFAULT_MAX_SYN_BACKLOG;
        init();
    }

    protected void init() {
        listenSock = init(new TcpSock());
        listenSock.state(TcpConnectionState.TCP_LISTEN);
    }

    protected abstract TcpSock init(TcpSock sk);

    public abstract void tcp_rcv(ChannelHandlerContext net, TcpPacketBuf pkt);

    public abstract void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err);

    public abstract void inet_rtx_syn_ack(ChannelHandlerContext net, TcpSock listenSock, tcp_request_sock req);

    protected abstract tcp_request_sock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt);

    protected abstract TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, tcp_request_sock req);

    /**
     * 对齐 Linux {@code __inet_lookup_skb}(net/ipv4/inet_hashtables.c):先查 ESTABLISHED 槽,
     * miss 再查 TIME_WAIT 槽(v2 的 {@link #timewaitRegistry}),最后回退到半连接 /
     * LISTEN。返回 {@link TcpTimewaitSock} 时由 {@code tcp_v4_rcv} 派发到
     * {@code tcp_timewait_state_process}。
     */
    protected SockCommon __inet_lookup_skb(final TcpPacketBuf pkt) {
        final FourTuple key = FourTuple.of(pkt);
        TcpSock established = establishedRegistry.get(key);
        if (established != null) {
            return established;
        }
        TcpTimewaitSock tw = timewaitRegistry.get(key);
        if (tw != null) {
            return tw;
        }
        tcp_request_sock req = synRegistry.get(key);
        if (req != null) {
            return req;
        }
        return listenSock;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     */
    public TcpSock tcp_check_req(ChannelHandlerContext net,
                                 TcpSock listenSock,
                                 TcpPacketBuf pkt,
                                 tcp_request_sock req) {
        final TcpHandshaker handshaker = req.request();

        // Step 1: RST — 仅当 SEQ == rcv_nxt 才销毁半开连接,其他 RST 静默丢弃
        if (pkt.isRst()) {
            if (pkt.tcpSeq() == handshaker.rcvNxt()) {
                inet_csk_destroy_sock(req);
            }
            return null;
        }

        // Step 2: SYN 重传 — 仅当 SEQ == rcv_isn 且 SYN-ACK 已发送(rsk_timer != null 在 v2 中等价为 synAckSent())
        if (pkt.isSyn()) {
            if (pkt.tcpSeq() == handshaker.rcvIsn() && handshaker.synAckSent()) {
                inet_rtx_syn_ack(net, listenSock, req);
                req.num_retrans++;
            }
            return null;
        }

        // Step 3: 仅 ACK 可完成握手
        if (!pkt.isAck()) {
            return null;
        }

        // Step 4: ACK 必须恰好确认 SYN-ACK (snt_isn + 1),否则发送 RST 但保留 req(v1 由后续超时回收)
        if (pkt.tcpAckNum() != handshaker.sndIsn() + 1) {
            send_reset(net, pkt, -1);
            return null;
        }

        // Step 5: 校验通过 — 创建子 sock
        TcpSock child = syn_recv_sock(net, listenSock, pkt, req);
        if (child != null) {
            // tcp_synack_rtt_meas:用 (tcp_clock_us - snt_synack) 作为首个 RTT 样本
            // 对应 Linux net/ipv4/tcp_input.c tcp_synack_rtt_meas;Karn's rule 要求 num_retrans == 0
            tcp_synack_rtt_meas(child, req);
        }
        return child;
    }

    /**
     * 三次握手完成时用 SYN-ACK 首发到 ACK 的时间差作为首个 RTT 样本。
     * Karn's rule:若 SYN-ACK 曾重传(num_retrans > 0),不取样(无法区分 ACK 确认的是哪一次)。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_synack_rtt_meas</a>
     */
    private static void tcp_synack_rtt_meas(TcpSock child, tcp_request_sock req) {
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

    protected void addToHalfQueue(final TcpSock listenSock, final tcp_request_sock req) {
        synRegistry.putIfAbsent(req.fourTuple(), req);
    }

    protected void moveToEstablished(final tcp_request_sock req, final TcpSock sock) {
        if (req.synPacket() != null) {
            req.synPacket().release();
            req.synPacket(null);
        }
        synRegistry.remove(req.fourTuple(), req);
        establishedRegistry.put(sock.fourTuple(), sock);
    }

    public void tcp_done(TcpSock tp) {
        if (!tp.hasConnection()) {
            return;
        }
        tp.state(TcpConnectionState.TCP_CLOSED);
        tp.skShutdown(SHUTDOWN_MASK);
        inet_csk_destroy_sock(tp);
    }

    public void inet_csk_destroy_sock(TcpSock sk) {
        if (!sk.hasConnection()) {
            return;
        }
        sk.close();
        establishedRegistry.remove(sk.fourTuple(), sk);
    }

    public void inet_csk_destroy_sock(tcp_request_sock req) {
        req.request().cancelRetransmitTimer();
        if (req.connectFuture() != null && req.connectFuture().channel() != null) {
            if (req.handshakeCloseListener() != null) {
                req.connectFuture().channel().closeFuture().removeListener(req.handshakeCloseListener());
            }
            req.connectFuture().channel().close();
        }
        if (req.childChannel() != null && req.childChannel().isOpen()) {
            if (req.handshakeCloseListener() != null) {
                req.childChannel().closeFuture().removeListener(req.handshakeCloseListener());
            }
            req.childChannel().close();
        }
        if (req.synPacket() != null) {
            req.synPacket().release();
            req.synPacket(null);
        }
        synRegistry.remove(req.fourTuple(), req);
    }

    public boolean tcp_close_state(TcpSock sk) {
        if (!sk.hasConnection()) {
            return false;
        }
        int next = NEW_STATE[sk.state().ordinal() + 1];
        int ns = next & TCP_STATE_MASK;
        sk.state(TcpConnectionState.values()[ns]);
        return 0 != (next & TCP_ACTION_FIN);
    }

    public void tcp_time_wait(ChannelHandlerContext ctx, TcpSock tp, TcpConnectionState state) {
        tcp_time_wait(tp, state, config.timeWaitMs());
    }

    /**
     * 对齐 Linux {@code tcp_time_wait}(net/ipv4/tcp_minisocks.c):
     * 从重量级 {@link TcpSock} 中摘出 TIME_WAIT 阶段所需的最小快照构建 {@link TcpTimewaitSock},
     * 注册到 {@link #timewaitRegistry} 并安排 2MSL 定时器;原 {@link TcpSock} 立即销毁,
     * 释放发送 / 接收缓冲、取消所有定时器,腾出 {@link #establishedRegistry} 中的槽位。
     *
     * <p>{@code state} 参数对齐 Linux {@code tcp_time_wait(sk, state, timeo)} 用于
     * 区分 FIN_WAIT_2 / TIME_WAIT 子状态 — 值会写入
     * {@link TcpTimewaitSock#tw_substate},由 {@code tcp_timewait_state_process}
     * 根据该字段分派(FIN_WAIT_2 等对端 FIN,TIME_WAIT 静默重放 ACK)。二者到期行为
     * 均为 {@link #inet_twsk_kill}。
     *
     * <p>迟到段重放 FIN-ACK 的通路由 {@code tcp_timewait_state_process} +
     * {@code TcpOutput.tcp_timewait_send_ack} 承担,共享 TUN 侧 channel,无需 {@link TcpSock}。
     */
    public void tcp_time_wait(TcpSock tp, TcpConnectionState state, long timeoutMs) {
        if (!tp.hasConnection()) {
            return;
        }
        final FourTuple ft = tp.fourTuple();
        final TcpTimewaitSock tw = new TcpTimewaitSock(
                ft,
                tp.channel(),
                tp.rcvNxt(),
                tp.sndNxt(),
                tp.rcvWnd(),
                tp.rcvWscale(),
                tp.timestampEnabled(),
                tp.recentTimestamp() & 0xFFFFFFFFL,
                tp.tsRecentStamp());
        // 对齐 Linux tcp_time_wait(sk, state, timeo):state ∈ {FIN_WAIT_2, TIME_WAIT}
        tw.tw_substate = (state == TcpConnectionState.FIN_WAIT_2)
                ? TcpConnectionState.FIN_WAIT_2
                : TcpConnectionState.TIME_WAIT;

        final long delay = Math.max(timeoutMs, 1L);
        tw.tw_timeout = System.currentTimeMillis() + delay;
        timewaitRegistry.put(ft, tw);
        com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats.INSTANCE.inc(
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib.TCPTIMEWAITCREATED);

        final EventLoop el = tp.eventLoop();
        if (el != null) {
            tw.tw_timer = el.schedule(() -> inet_twsk_kill(tw), delay, TimeUnit.MILLISECONDS);
        }

        // 原 TcpSock 下沉为 twsk 后立即销毁:取消所有定时器、释放缓冲、下架 ESTABLISHED 槽
        tp.state(TcpConnectionState.TCP_CLOSED);
        tp.skShutdown(SHUTDOWN_MASK);
        inet_csk_destroy_sock(tp);
    }

    /**
     * 对齐 Linux {@code inet_twsk_kill}(net/ipv4/inet_timewait_sock.c):从 TW bucket 移除,
     * 取消挂起的 2MSL 定时器。线程归属:必须在 twsk 关联 EventLoop 上调用(v2 当前从
     * 事件循环派发任务或在 2MSL 到期处自然触发,均满足)。
     */
    public void inet_twsk_kill(TcpTimewaitSock tw) {
        if (tw == null) {
            return;
        }
        timewaitRegistry.remove(tw.fourTuple(), tw);
        java.util.concurrent.ScheduledFuture<?> f = tw.tw_timer;
        if (f != null && !f.isDone()) {
            f.cancel(false);
        }
        tw.tw_timer = null;
    }

    /**
     * 对齐 Linux {@code inet_twsk_reschedule}(net/ipv4/inet_timewait_sock.c):收到迟到 FIN
     * 重放 ACK 后刷新 2MSL 定时器。
     */
    public void inet_twsk_reschedule(TcpTimewaitSock tw, long timeoutMs) {
        if (tw == null) {
            return;
        }
        java.util.concurrent.ScheduledFuture<?> prev = tw.tw_timer;
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
        final long delay = Math.max(timeoutMs, 1L);
        tw.tw_timeout = System.currentTimeMillis() + delay;
        final Channel ch = tw.tw_channel;
        if (ch != null && ch.eventLoop() != null) {
            tw.tw_timer = ch.eventLoop().schedule(() -> inet_twsk_kill(tw), delay, TimeUnit.MILLISECONDS);
        }
    }

    protected void tcp_shutdown(TcpSock sk, int how) {
        if (!sk.hasConnection() || (how & TcpConstants.SEND_SHUTDOWN) == 0) {
            return;
        }
        if (sk.state() == TcpConnectionState.TCP_ESTABLISHED
                || sk.state() == TcpConnectionState.CLOSE_WAIT) {
            if (tcp_close_state(sk)) {
                TcpOutput.INSTANCE.tcp_send_fin(sk);
            }
        }
    }

    public void consume(final ChannelHandlerContext ctx, final TcpPacketBuf pkt) {
        tcp_rcv(ctx, pkt);
    }

    public boolean sk_acceptq_is_full() {
        return synRegistry.size() >= maxSynBacklog;
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        TcpSock sk = establishedRegistry.get(key);
        if (sk == null || !sk.hasConnection() || !sk.state().canSend()) {
            data.release();
            return false;
        }
        enqueueWrite(sk, data, true);
        return true;
    }

    protected int tcp_ack(TcpSock sk, TcpPacketBuf pkt, int flag) {
        if (!sk.hasConnection() || !pkt.isAck()) {
            return 1;
        }
        return TcpIncomingAckHandler.tcpAck(sk, pkt, flag);
    }

    /**
     * 对齐 Linux {@code tcp_data_queue} (tcp_input.c:5229) 的七分支判定:
     * <ol>
     *   <li>{@code seq == rcv_nxt} 且窗口 &gt; 0 → 顺序入队 {@code queue_and_out};</li>
     *   <li>{@code seq == rcv_nxt} 且窗口 = 0 且携带 FIN(无 payload)→ 仍接受 FIN;</li>
     *   <li>{@code seq == rcv_nxt} 且窗口 = 0 且非纯 FIN → 按零窗口丢弃;</li>
     *   <li>{@code end_seq <= rcv_nxt} → 完整重传段丢弃(OLD_DATA);</li>
     *   <li>{@code seq >= rcv_nxt + rwnd} → 越窗丢弃(OVERWINDOW);</li>
     *   <li>{@code seq < rcv_nxt < end_seq} → 部分重叠,窗口 0 丢尾否则入队;</li>
     *   <li>其它 → 纯乱序,入 OFO 队列。</li>
     * </ol>
     * FIN 的状态机迁移由 {@code queue_and_out} 内 {@code tcp_fin_state_process} 承担。
     */
    protected int tcp_data_queue(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
        if (!sk.hasConnection()) {
            return 0;
        }
        final int seq = pkt.tcpSeq();
        final int endSeq = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq(pkt);
        // 纯 ACK / SYN-only 段已在 pre-validator 或状态机上游剥离;此处若无 seq 消耗直接返回
        if (seq == endSeq) {
            return 0;
        }
        if (!sk.state().canReceive()) {
            return 0;
        }

        final int rcvNxt = sk.rcvNxt();
        final int rwnd = sk.tcp_receive_window();

        if (seq == rcvNxt) {
            if (rwnd == 0) {
                if (pkt.tcpPayloadLength() == 0 && pkt.isFin()) {
                    // 即使通告零窗口,仍接受裸 FIN(v1 L1617-1618)
                    queue_and_out(ctx, sk, pkt);
                } else {
                    tcp_out_of_window(sk, SkbDropReasonConstants.SKB_DROP_REASON_TCP_ZEROWINDOW);
                }
                return 0;
            }
            queue_and_out(ctx, sk, pkt);
            return 0;
        }

        if (!after(endSeq, rcvNxt)) {
            // 完整重传段(纯 dup)
            tcp_out_of_window(sk, SkbDropReasonConstants.SKB_DROP_REASON_TCP_OLD_DATA);
            return 0;
        }

        if (!before(seq, rcvNxt + rwnd)) {
            // seq 在窗口外(零窗口探测等)
            tcp_out_of_window(sk, SkbDropReasonConstants.SKB_DROP_REASON_TCP_OVERWINDOW);
            return 0;
        }

        if (before(seq, rcvNxt)) {
            // 部分重叠: seq < rcv_nxt < end_seq
            if (rwnd == 0) {
                tcp_out_of_window(sk, SkbDropReasonConstants.SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                queue_and_out(ctx, sk, pkt);
            }
            return 0;
        }

        // 纯乱序: seq > rcv_nxt && seq < rcv_nxt + rwnd
        tcp_data_queue_ofo(sk, pkt);
        return 0;
    }

    /**
     * 等价 Linux {@code queue_and_out} (tcp_input.c:5150) — 将段交付上层,
     * 推进 {@code rcv_nxt},若段自身或被 FIN 填洞后的 OFO 排水释放出 FIN,
     * 则触发 {@code tcp_fin} 状态转移;最后按快慢判定 ACK 调度策略。
     *
     * <p>v2 拆分:数据入 {@link TcpReceiveBuffer}(含 OFO FIN 感知),
     * 是否触发 {@code tcp_fin} 由 {@link TcpReceiveBuffer.OfferResult#finDelivered}
     * 决定 — 避免乱序 FIN 提前进入 CLOSE_WAIT。
     */
    private void queue_and_out(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
        final int priorRcvNxt = sk.rcvNxt();
        final int payloadLen  = pkt.tcpPayloadLength();
        final int seq         = pkt.tcpSeq();
        final int endSeq      = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq(pkt);
        final boolean fin     = pkt.isFin();

        if (payloadLen > 0) {
            sk.onDataReceived(payloadLen);
        }

        final ByteBuf payload = pkt.tcpPayloadSlice();
        final ByteBuf segment = payloadLen > 0
                ? payload.retainedSlice()
                : io.netty.buffer.Unpooled.EMPTY_BUFFER;

        final TcpReceiveBuffer.OfferResult r = sk.receiveBuffer()
                .offer(seq, endSeq, priorRcvNxt, segment, fin);
        sk.rcvNxt(r.rcvNxt);

        if (sk.receiveBuffer().isReadable()) {
            consume(sk, sk.receiveBuffer().readAll());
        }

        if (r.finDelivered) {
            // 当前段或先前 OFO 中的 FIN 已抵达 rcv_nxt,执行状态迁移并回 ACK。
            tcp_fin(ctx, sk);
            return;
        }

        // ACK 调度: 乱序恢复(OFO 被排空)或本段非顺位 → ACK_NOW + quickack;否则 schedule。
        if (payloadLen > 0) {
            boolean ofoDrain = r.rcvNxt != priorRcvNxt + payloadLen;
            if (ofoDrain || seq != priorRcvNxt) {
                sk.enterQuickAckMode(TcpConstants.TCP_INIT_CWND);
                sk.addAckPending(TcpConstants.ACK_NOW);
            } else {
                sk.addAckPending(TcpConstants.ACK_SCHED);
            }
        }
    }

    /**
     * 对齐 Linux {@code tcp_fin}(tcp_input.c:4318)全状态 switch。
     *
     * <p>调用约定:{@code rcv_nxt} 必须已由上游推进到 FIN 之后 —
     * {@link #queue_and_out} 通过 {@code receiveBuffer.offer} 推进,
     * {@code tcp_rcv_state_process} 非数据路径(CLOSE_WAIT/CLOSING/LAST_ACK 的
     * 重传 FIN)走过时不需要推进(seq &lt; rcv_nxt)。
     *
     * <ul>
     *   <li><b>SYN_RECV / ESTABLISHED</b>:迁移至 {@link TcpConnectionState#CLOSE_WAIT},
     *       置 pingpong,发 ACK。v2 正常路径下 SYN_RECV + FIN+ACK 会先由
     *       {@code tcp_rcv_state_process} 的 SYN_RECV 分支 {@code tcp_try_establish} 迁入
     *       ESTABLISHED,再进入 {@code tcp_data_queue} 命中 ESTABLISHED case;与 Linux
     *       {@code tcp_rcv_state_process} 先 {@code tcp_set_state(ESTABLISHED)} fall-through
     *       到末尾 {@code tcp_data_queue} 的路径等价。两侧 SYN_RECV case 均作防御性合并保留。</li>
     *   <li><b>CLOSE_WAIT / CLOSING / LAST_ACK</b>:重传 FIN,状态保持,发 ACK。</li>
     *   <li><b>FIN_WAIT_1</b>:simultaneous close,先发 ACK 再迁 {@link TcpConnectionState#CLOSING}
     *       (对齐 Linux tcp_input.c:4355)。</li>
     *   <li><b>FIN_WAIT_2</b>:4-way close 完成,发最后 ACK 并 {@link #tcp_time_wait}
     *       (sk 随后被销毁,调用方不应再触碰)。</li>
     *   <li><b>default</b>:LISTEN / SYN_SENT / CLOSED / TIME_WAIT — v2 架构下不应到达,no-op。</li>
     * </ul>
     */
    protected void tcp_fin(ChannelHandlerContext ctx, TcpSock sk) {
        sk.addShutdown(TcpConstants.RCV_SHUTDOWN);
        switch (sk.state()) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                sk.state(TcpConnectionState.CLOSE_WAIT);
                sk.enterPingpongMode();
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                return;
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                return;
            case FIN_WAIT_1:
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                sk.state(TcpConnectionState.CLOSING);
                return;
            case FIN_WAIT_2:
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                tcp_time_wait(ctx, sk, TcpConnectionState.TIME_WAIT);
                return;
            default:
                // TCP_LISTEN / TCP_SYN_SENT / TCP_CLOSED / TIME_WAIT — 不应到达,防御性 no-op
                return;
        }
    }

    /**
     * 越窗 / 过旧段统一丢弃路径 — 对齐 Linux {@code out_of_window}(tcp_input.c:5202)
     * 和 {@code NET_INC_STATS(net, LINUX_MIB_OUTOFWINDOWICMPS)} + 尾部
     * {@code kfree_skb_reason(skb, reason)} 投递。
     */
    protected void tcp_out_of_window(TcpSock sk, int reason) {
        sk.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        sk.addAckPending(TcpConstants.ACK_SCHED);
        com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats.INSTANCE.inc(
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib.OUTOFWINDOWICMPS);
        com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats.INSTANCE.incDrop(reason);
    }

    /**
     * 纯乱序段(seq &gt; rcv_nxt) 入 OFO 队列(等价 Linux {@code tcp_data_queue_ofo}
     * tcp_input.c:5055)。v2 通过 {@link TcpReceiveBuffer#offerOfo} 完成:
     * <ul>
     *   <li>保留 FIN 语义以便后续填洞时重放 {@code tcp_fin};</li>
     *   <li>受 {@link TcpReceiveBuffer#OFO_MAX_BYTES} 内存预算限制,超限时先 prune 再判定。</li>
     * </ul>
     * 入队后统一 quickack + ACK_NOW(对齐 Linux OFO 到达立即回 DSACK 行为)。
     */
    protected void tcp_data_queue_ofo(TcpSock sk, TcpPacketBuf pkt) {
        final int seq     = pkt.tcpSeq();
        final int endSeq  = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq(pkt);
        final boolean fin = pkt.isFin();

        if (seq == endSeq) {
            return;
        }

        final ByteBuf payload    = pkt.tcpPayloadSlice();
        final int     payloadLen = payload.readableBytes();
        final ByteBuf segment    = payloadLen > 0
                ? payload.retainedSlice()
                : io.netty.buffer.Unpooled.EMPTY_BUFFER;

        boolean queued = sk.receiveBuffer().offerOfo(seq, endSeq, segment, fin);
        if (!queued) {
            // 内存紧张或完全被覆盖,保持接收窗口外语义不变
            return;
        }

        // OFO 段抵达:立即 quickack 并安排 ACK_NOW 以触发 SACK/DSACK 通告
        sk.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        sk.addAckPending(TcpConstants.ACK_NOW);
    }

    protected void tcp_data_snd_check(TcpSock sk) {
        tcp_push_pending_frames(sk);
    }

    protected void tcp_push_pending_frames(TcpSock sk) {
        if (sk.hasConnection() && sk.tcpSendHead() != null) {
            boolean needProbe = TcpOutput.INSTANCE.tcp_write_xmit(sk, sk.mss(), TCP_NAGLE_OFF, 0);
            if (needProbe) {
                armProbe0(sk);
            }
        }
    }

    protected static boolean tcp_sequence_acceptable(TcpSock sk, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = sk.rcvWup();
        int rcvWndEnd = sk.rcvNxt() + sk.tcp_receive_window();

        if (before(endSeq, rcvWup)) {
            return false;
        }
        if (after(endSeq, rcvWndEnd)) {
            return !after(seq, rcvWndEnd);
        }
        return true;
    }

    protected static void tcp_init_wl(TcpSock sk, int seq) {
        if (sk.hasConnection()) {
            sk.sndWl1(seq);
        }
    }

    protected void tcp_init_transfer(TcpSock sk) {
        if (sk == null || !sk.hasBackendChannel()) {
            return;
        }
        sk.probeTimerAction(() -> tcp_probe_timer(sk));
        sk.keepaliveTimerAction(() -> tcp_keepalive_timer(sk));

        final Channel childChannel = sk.childChannel();
        final ChannelFutureListener handshakeCloseListener = sk.childCloseListener();
        sk.childCloseListener(future -> {
            final TcpConnectionState state = sk.state();
            if (tcp_close_state(sk)) {
                TcpOutput.INSTANCE.tcp_send_fin(sk);
            }
        });

        childChannel.closeFuture().addListener(sk.childCloseListener());
        if (handshakeCloseListener != null) {
            childChannel.closeFuture().removeListener(handshakeCloseListener);
        }
        childChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                try {
                    final ByteBuf buf = (ByteBuf) msg;
                    final int mss = TcpOutput.INSTANCE.tcp_current_mss(sk);
                    final int total = buf.readableBytes();

                    for (int offset = 0; offset < total; ) {
                        final int len = Math.min(total - offset, mss);
                        final boolean flush = offset + len >= total;
                        enqueueWrite(sk, buf.retainedSlice(buf.readerIndex() + offset, len), flush);
                        offset += len;
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                TcpOutput.INSTANCE.tcp_send_reset(sk);
                tcp_done(sk);
                if (ctx.channel().isOpen()) {
                    ctx.channel().close();
                }
            }
        });
        childChannel.config().setAutoRead(true);
        armKeepalive(sk, sk.keepaliveTimeMs());
    }

    protected static int tcp_initialize_rcv_mss(TcpSock sk) {
        if (!sk.hasConnection()) {
            return TCP_MSS_DEFAULT;
        }
        int mss = sk.mss();
        int hint = Math.min(mss, sk.rcvWnd() / 2);
        hint = Math.min(hint, TCP_INIT_CWND * mss);
        return Math.max(hint, TCP_MSS_DEFAULT);
    }

    protected void tcp_ack_snd_check(TcpSock sk) {
        if (!sk.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_NOW)) {
            return;
        }
        if ((after(sk.rcvNxt(), sk.rcvWup() + sk.rcvMss()) && sk.rcvWnd() >= sk.tcp_receive_window())
                || sk.inQuickAckMode()
                || sk.hasAckPending(TcpConstants.ACK_NOW)) {
            TcpOutput.INSTANCE.tcp_send_ack(sk);
            return;
        }
        tcp_send_delayed_ack(sk);
    }

    protected void tcp_send_delayed_ack(TcpSock sk) {
        long ato = Math.max(1L, sk.ackTimeoutMs());
        if (sk.inPingpongMode()) {
            ato = Math.max(ato, config.delayedAckMs());
        }
        sk.addAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(sk, ato, () -> tcp_delack_timer(sk));
    }

    protected void tcp_delack_timer(TcpSock sk) {
        if (!sk.hasConnection() || !sk.hasAckPending(TcpConstants.ACK_TIMER)) {
            return;
        }
        sk.clearAckPending(TcpConstants.ACK_TIMER);
        if (!sk.hasAckPending(TcpConstants.ACK_SCHED)) {
            return;
        }
        if (!sk.inPingpongMode()) {
            sk.ackTimeoutMs(Math.min(sk.ackTimeoutMs() << 1, sk.rtoMs()));
        } else {
            sk.exitPingpongMode();
            sk.ackTimeoutMs(TcpConstants.TCP_ATO_MIN_MS);
        }
        TcpOutput.INSTANCE.tcp_send_ack(sk);
    }

    protected void armProbe0(TcpSock sk) {
        if (!sk.hasConnection() || sk.packetsOut() != 0 || sk.tcpSendHead() == null) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sk,
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType.ZERO_WINDOW_PROBE,
                sk.tcpProbe0BaseMs(),
                () -> tcp_probe_timer(sk)
        );
    }

    protected void tcp_probe_timer(TcpSock sk) {
        if (!sk.hasConnection()) {
            return;
        }
        if (sk.packetsOut() > 0 || sk.tcpSendHead() == null) {
            sk.resetProbeState();
            return;
        }

        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        if (sk.probesTstampMs() == 0L) {
            sk.probesTstampMs(now);
        } else if (sk.userTimeoutMs() > 0 && now - sk.probesTstampMs() >= sk.userTimeoutMs()) {
            sk.skErr(110);
            TcpOutput.INSTANCE.tcp_send_reset(sk);
            tcp_done(sk);
            return;
        }

        if (sk.probesOut() >= TcpConstants.TCP_RETRIES2) {
            sk.skErr(110);
            TcpOutput.INSTANCE.tcp_send_reset(sk);
            tcp_done(sk);
            return;
        }

        long timeout = TcpOutput.INSTANCE.tcp_send_probe0(sk);
        if (timeout > 0L) {
            TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                    sk,
                    com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TimerType.ZERO_WINDOW_PROBE,
                    timeout,
                    () -> tcp_probe_timer(sk)
            );
        }
    }

    protected void armKeepalive(TcpSock sk, long delayMs) {
        if (!sk.hasConnection()
                || !sk.keepaliveEnabled()
                || sk.state() == TcpConnectionState.TIME_WAIT
                || sk.state() == TcpConnectionState.TCP_CLOSED
                || sk.state() == TcpConnectionState.TCP_LISTEN
                || sk.state() == TcpConnectionState.TCP_SYN_RECV) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleKeepalive(sk, Math.max(delayMs, 1L), () -> tcp_keepalive_timer(sk));
    }

    protected void tcp_keepalive_timer(TcpSock sk) {
        if (!sk.hasConnection() || !sk.keepaliveEnabled()) {
            return;
        }

        if (sk.state() == TcpConnectionState.FIN_WAIT_2) {
            return;
        }

        if (sk.packetsOut() > 0 || sk.tcpSendHead() != null) {
            armKeepalive(sk, sk.keepaliveTimeMs());
            return;
        }

        long elapsed = sk.keepaliveElapsedMs();
        if (elapsed < sk.keepaliveTimeMs()) {
            armKeepalive(sk, sk.keepaliveTimeMs() - elapsed);
            return;
        }

        long userTimeout = sk.userTimeoutMs();
        if ((userTimeout > 0L && elapsed >= userTimeout && sk.probesOut() > 0)
                || (userTimeout == 0L && sk.probesOut() >= sk.keepaliveProbes())) {
            sk.skErr(110);
            TcpOutput.INSTANCE.tcp_send_reset(sk);
            tcp_done(sk);
            return;
        }

        int err = TcpOutput.INSTANCE.tcp_write_wakeup(sk, 1);
        long next;
        if (err <= 0) {
            sk.probesOut(sk.probesOut() + 1);
            next = sk.keepaliveIntvlMs();
        } else {
            next = TcpConstants.TCP_RESOURCE_PROBE_INTERVAL_MS;
        }
        armKeepalive(sk, next);
    }

    protected void consume(TcpSock sk, ByteBuf data) {
        if (sk != null && sk.hasBackendChannel()) {
            sk.childChannel().writeAndFlush(data);
            return;
        }
        dataConsumer.onData(sk.fourTuple(), data);
    }

    protected void enqueueWrite(TcpSock sk, ByteBuf data, boolean flush) {
        final Runnable task = () -> {
            if (!sk.hasConnection() || !sk.state().canSend()) {
                data.release();
                return;
            }
            sk.tcp_queue_skb(new TcpSkb(
                    data,
                    sk.writeSeq(),
                    data.readableBytes(),
                    (byte) TcpConstants.TCPHDR_ACK,
                    0L));
            if (flush) {
                tcp_push_pending_frames(sk);
            }
        };

        final EventLoop owner = sk.eventLoop();
        if (owner != null && !owner.inEventLoop()) {
            owner.execute(task);
        } else {
            task.run();
        }
    }

    /**
     * 套接字族根类 — v2 统一 {@link TcpSock}(完整 ESTABLISHED 态)、
     * {@link tcp_request_sock}(半连接态)和 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpTimewaitSock}
     * (TIME_WAIT 迷你态)为共同基类,使 {@link #__inet_lookup_skb} 能返回统一类型,
     * 对应 Linux {@code struct sock_common} 的角色。
     */
    public abstract static class SockCommon {
        private final FourTuple fourTuple;

        protected SockCommon(FourTuple fourTuple) {
            this.fourTuple = fourTuple;
        }

        public FourTuple fourTuple() {
            return fourTuple;
        }

        public abstract TcpConnectionState state();

        public abstract void state(TcpConnectionState state);
    }

    public static class TcpSock extends SockCommon {
        private TcpConnection conn;
        private Channel channel;
        private Channel childChannel;
        private ChannelFutureListener childCloseListener;
        private TcpSendBuffer sendBuffer;
        private TcpReceiveBuffer receiveBuffer;
        private TcpConnectionTimers timers;
        private final Map<ConnectionKey<?>, Object> attributes = new HashMap<>();
        private TcpConnectionState state;
        private int sndUna;
        private int sndNxt;
        private int writeSeq;
        private int rcvNxt;
        private int sndWnd;
        private int maxWindow;
        private int sndWl1;
        private int sndSml;
        private int rcvWnd;
        private int rcvWup;
        private int rcvMss;
        private int mss;
        private int sndWscale;
        private int rcvWscale;
        private long bytesAcked;
        private int packetsOut;
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
        private int rtoBackoffShift;
        /**
         * 首个重传段的发送时戳(微秒);当 {@code undoRetrans} 减至 0 时清零。
         * 对应 Linux {@code tp->retrans_stamp},用于 RTO 退避 / F-RTO / tcp_any_retrans_done 判定。
         */
        private long retransStamp;
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
        private int sackedOut;
        /**
         * 被 RACK / FACK-like 路径标记为 LOST 的段数(非字节数)。
         * 对应 Linux {@code tp->lost_out};由 {@code tcp_mark_skb_lost} 增,
         * {@code tcp_clean_rtx_queue} 释放带 {@code TCPCB_LOST} 位的段时减。
         */
        private int lostOut;
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
        private int cwnd = TcpConstants.TCP_INIT_CWND;
        private int ssthresh = Integer.MAX_VALUE;
        /**
         * cwnd 最近一次被 {@code tcp_cwnd_validate} 确认的毫秒时戳 —
         * 对应 Linux {@code tp->snd_cwnd_stamp}(单位 {@code tcp_jiffies32})。
         * app-limited 时判定是否到了 {@code tcp_cwnd_application_limited} 回收点。
         */
        private long sndCwndStampMs;
        /**
         * non-cwnd-limited 期间 packets_out 的最高水位 — 对应 Linux
         * {@code tp->snd_cwnd_used}。application-limited 回收时取
         * {@code max(snd_cwnd_used, tcp_init_cwnd)} 作为新 cwnd 下沿。
         */
        private int sndCwndUsed;
        /**
         * 当前窗口是否 cwnd 受限 — 对应 Linux {@code tp->is_cwnd_limited}。
         * 置位后持续到下一次 {@code tcp_cwnd_application_limited} 执行才清理。
         */
        private boolean isCwndLimited;
        private int dupacks;
        private int caIncrCounter;
        private CongestionState congestionState = CongestionState.OPEN;
        private int highSeq;
        private int tlpHighSeq;

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
            sock.channel = channel;
            sock.childChannel = childChannel;
            sock.sendBuffer = new TcpSendBuffer();
            sock.receiveBuffer = new TcpReceiveBuffer(channel.alloc());
            sock.timers = new TcpConnectionTimers();
            sock.state = TcpConnectionState.TCP_SYN_RECV;
            sock.sndUna = sndUna;
            sock.sndNxt = sndNxt;
            sock.writeSeq = sndNxt;
            sock.rcvNxt = rcvNxt;
            sock.sndWnd = sndWnd;
            sock.maxWindow = sndWnd;
            sock.sndWl1 = 0;
            sock.sndSml = sndUna;
            sock.rcvWnd = rcvWnd;
            sock.rcvWup = rcvNxt;
            sock.rcvMss = mss;
            sock.mss = mss;
            sock.sndWscale = sndWscale;
            sock.rcvWscale = rcvWscale;
            sock.initInlineTcpState();
            sock.timestampEnabled = timestampEnabled;
            sock.recentTimestamp = recentTimestamp;
            // 子 sock 由 SYN 中 TSval 初始化 ts_recent,刷新时戳也在此点建立
            sock.tsRecentStamp = timestampEnabled ? nowSeconds() : 0;
            // 接收侧窗口预算初始化(对齐 Linux tcp_init_buffer_space):
            // rcvBuf / windowClamp 取当前通告窗口,rcvSsthresh 同步(无缩放时退化为 rcvWnd)
            sock.rcvBuf = Math.max(rcvWnd, TcpConstants.TCP_DEFAULT_RCV_BUF);
            sock.windowClamp = Math.max(rcvWnd, TcpConstants.TCP_DEFAULT_RCV_BUF);
            sock.rcvSsthresh = Math.max(rcvWnd, TcpConstants.TCP_DEFAULT_RCV_BUF);
            // PMTU / tcp_header_len 初始化(对齐 Linux tcp_create_openreq_child):
            //   tcp_header_len = 20 + (tstamp_ok ? TCPOLEN_TSTAMP_ALIGNED(12) : 0)
            //   mssCache 延迟到首次 tcp_sync_mss;pmtuCookie/dstMtu 默认 0(无 PMTU 发现)
            sock.tcpHeaderLen = TcpConstants.TCP_MIN_HEADER_LEN
                    + (timestampEnabled ? TcpConstants.TCP_TSOPT_WIRE_LEN : 0);
            sock.mssCache   = mss;
            sock.pmtuCookie = 0;
            sock.dstMtu     = 0;
            sock.sackedOut  = 0;
            sock.lostOut    = 0;
            sock.rackMstamp = 0L;
            sock.rackRttUs  = 0L;
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
            rtoBackoffShift = 0;
            retransStamp = 0L;
            undoRetrans = 0;
            rcvBuf = TcpConstants.TCP_DEFAULT_RCV_BUF;
            windowClamp = TcpConstants.TCP_DEFAULT_RCV_BUF;
            rcvSsthresh = TcpConstants.TCP_DEFAULT_RCV_BUF;
            mssCache = 0;
            pmtuCookie = 0;
            tcpHeaderLen = 0;
            dstMtu = 0;
            sackedOut = 0;
            lostOut = 0;
            rackMstamp = 0L;
            rackRttUs = 0L;
            linger2 = (int) TcpConstants.FIN_WAIT_2_TIMEOUT_MS;
            probeBackoffShift = 0;
            probesOut = 0;
            probesTstampMs = 0L;
            userTimeoutMs = 0L;
            keepaliveTimeMs = TcpConstants.TCP_KEEPALIVE_TIME_MS;
            keepaliveIntvlMs = TcpConstants.TCP_KEEPALIVE_INTVL_MS;
            keepaliveProbes = TcpConstants.TCP_KEEPALIVE_PROBES;
            keepaliveEnabled = false;
            cwnd = TcpConstants.TCP_INIT_CWND;
            ssthresh = Integer.MAX_VALUE;
            sndCwndStampMs = 0L;
            sndCwndUsed = 0;
            isCwndLimited = false;
            dupacks = 0;
            caIncrCounter = 0;
            congestionState = CongestionState.OPEN;
            highSeq = 0;
            tlpHighSeq = 0;
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
            this.sndUna = conn.sndUna();
            this.sndNxt = conn.sndNxt();
            this.writeSeq = conn.writeSeq();
            this.rcvNxt = conn.rcvNxt();
            this.sndWnd = conn.sndWnd();
            this.maxWindow = conn.maxWindow();
            this.sndWl1 = conn.sndWl1();
            this.sndSml = conn.sndSml();
            this.rcvWnd = conn.rcvWnd();
            this.rcvWup = conn.rcvWup();
            this.rcvMss = conn.rcvMss();
            this.mss = conn.mss();
            this.sndWscale = conn.sndWscale();
            this.rcvWscale = conn.rcvWscale();
            this.bytesAcked = conn.bytesAcked();
            this.packetsOut = conn.packetsOut();
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
            this.rtoBackoffShift = conn.rtoBackoffShift();
            this.retransStamp = conn.retransStamp();
            this.undoRetrans = conn.undoRetrans();
            this.mssCache = conn.mssCache();
            this.pmtuCookie = conn.pmtuCookie();
            this.tcpHeaderLen = conn.tcpHeaderLen();
            this.dstMtu = conn.dstMtu();
            this.sackedOut = conn.sackedOut();
            this.cwnd = conn.cwnd();
            this.ssthresh = conn.ssthresh();
            this.sndCwndStampMs = conn.sndCwndStampMs();
            this.sndCwndUsed = conn.sndCwndUsed();
            this.isCwndLimited = conn.isCwndLimited();
            this.dupacks = conn.dupacks();
            this.caIncrCounter = conn.caIncrCounter();
            this.congestionState = conn.congestionState() == null
                    ? CongestionState.OPEN
                    : CongestionState.valueOf(conn.congestionState());
            this.highSeq = conn.highSeq();
            this.tlpHighSeq = conn.tlpHighSeq();
        }

        public Channel channel() {
            return channel;
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

        public EventLoop eventLoop() {
            return channel == null ? null : channel.eventLoop();
        }

        public int sndUna() {
            return sndUna;
        }

        public int sndNxt() {
            return sndNxt;
        }

        public void sndNxt(int v) {
            this.sndNxt = v;
        }

        public void sndUna(int v) {
            this.sndUna = v;
        }

        public int writeSeq() {
            return writeSeq;
        }

        public void writeSeq(int v) {
            this.writeSeq = v;
        }

        public int rcvNxt() {
            return rcvNxt;
        }

        public void rcvNxt(int v) {
            this.rcvNxt = v;
        }

        public int sndWnd() {
            return sndWnd;
        }

        public void sndWnd(int v) {
            this.sndWnd = v;
            if (Integer.compareUnsigned(v, maxWindow) > 0) {
                this.maxWindow = v;
            }
        }

        public int maxWindow() {
            return maxWindow;
        }

        public void maxWindow(int v) {
            this.maxWindow = v;
        }

        public int packetsOut() {
            return packetsOut;
        }

        public void packetsOut(int v) {
            this.packetsOut = v;
        }

        public int sndWl1() {
            return sndWl1;
        }

        public void sndWl1(int v) {
            this.sndWl1 = v;
        }

        public int sndSml() {
            return sndSml;
        }

        public void sndSml(int v) {
            this.sndSml = v;
        }

        public int rcvWnd() {
            return rcvWnd;
        }

        public void rcvWnd(int v) {
            this.rcvWnd = v;
        }

        public int rcvWup() {
            return rcvWup;
        }

        public void rcvWup(int v) {
            this.rcvWup = v;
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

        public int tcp_receive_window() {
            return Math.max(0, rcvWup + rcvWnd - rcvNxt);
        }

        public int sndUnaUpdate(int ackSeq) {
            if (!after(ackSeq, sndUna)) {
                return 0;
            }
            int delta = ackSeq - sndUna;
            sndUna = ackSeq;
            bytesAcked += delta;
            return delta;
        }

        public int acknowledgeUpTo(int ackSeq) {
            int delta = sndUnaUpdate(ackSeq);
            if (delta > 0 && sendBuffer != null) {
                packetsOut -= sendBuffer.acknowledgeUpTo(ackSeq);
            }
            return delta;
        }

        public void incrementPacketsOut() {
            packetsOut++;
        }

        public void decrementPacketsOut(int count) {
            packetsOut = Math.max(0, packetsOut - count);
        }

        public TcpSkb tcpSendHead() {
            return sendBuffer == null ? null : sendBuffer.peekWrite();
        }

        public void tcp_queue_skb(TcpSkb skb) {
            if (sendBuffer != null) {
                writeSeq = skb.endSeq();
                sendBuffer.enqueue(skb);
            }
        }

        public void cleanRtxQueue(int ackSeq) {
            if (sendBuffer != null) {
                packetsOut -= sendBuffer.acknowledgeUpTo(ackSeq);
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
            if (delta <= com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_PAWS_WINDOW) {
                return false;
            }
            // 24 天陈旧分支:基线过老,允许任意 TSval 覆盖
            if (tsRecentStamp != 0) {
                long ageSec = (long) (nowSeconds() - tsRecentStamp);
                if (ageSec >= com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants.TCP_PAWS_24DAYS_SEC) {
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
                tsRecentStamp = nowSeconds();
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
            rtoBackoffShift = 0;
        }

        public long rtoMs() {
            long baseUs;
            if (srttUs == 0) {
                baseUs = TcpConstants.RTO_INIT_MS * 1_000L;
            } else {
                baseUs = srttUs + Math.max(1_000L, 4 * rttvarUs);
            }
            long rtoMs = (baseUs << rtoBackoffShift) / 1_000L;
            return Math.min(Math.max(rtoMs, TcpConstants.RTO_MIN_MS), TcpConstants.RTO_MAX_MS);
        }

        public long srttUs() {
            return srttUs;
        }

        public void backoffRto() {
            if (rtoBackoffShift < 6) {
                rtoBackoffShift++;
            }
        }

        public void resetRtoBackoff() {
            rtoBackoffShift = 0;
        }

        /**
         * 首段重传的发送微秒时戳;0 表示当前无未确认的重传。
         * 对应 Linux {@code tp->retrans_stamp}。
         */
        public long retransStamp() {
            return retransStamp;
        }

        public void retransStamp(long us) {
            this.retransStamp = us;
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
         * 写入后下一次 {@link TcpOutput#tcp_current_mss} 会检测到与 {@link #pmtuCookie} 不一致,
         * 触发 {@link TcpOutput#tcp_sync_mss} 重新推导 {@link #mssCache}。
         */
        public int dstMtu() { return dstMtu; }
        public void dstMtu(int v) { this.dstMtu = Math.max(v, 0); }

        /** 对应 Linux {@code tp->sacked_out}。 */
        public int sackedOut() { return sackedOut; }
        public void sackedOut(int v) { this.sackedOut = Math.max(v, 0); }
        public void incrSackedOut() { this.sackedOut++; }
        public void decrSackedOut(int n) {
            if (n <= 0) return;
            this.sackedOut = Math.max(this.sackedOut - n, 0);
        }

        /** 对应 Linux {@code tp->lost_out}。 */
        public int lostOut() { return lostOut; }
        public void incrLostOut() { this.lostOut++; }
        public void decrLostOut(int n) {
            if (n <= 0) return;
            this.lostOut = Math.max(this.lostOut - n, 0);
        }

        /** RACK 最新 SACKed 段 {@code sentTimeUs};单调更新。 */
        public long rackMstamp() { return rackMstamp; }
        public void updateRack(long sentTimeUs, long rttUs) {
            if (sentTimeUs > rackMstamp) {
                rackMstamp = sentTimeUs;
                rackRttUs = Math.max(rttUs, 0L);
            }
        }
        public long rackRttUs() { return rackRttUs; }

        public int linger2() {
            return linger2;
        }

        public void linger2(int linger2) {
            this.linger2 = linger2;
        }

        public void incrQuickAckCount(int maxQuickAcks) {
            int quickacks = rcvWnd / Math.max(rcvMss << 1, 1);
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
                    ssthresh = Math.max(cwnd / 2, 2);
                    cwnd = ssthresh + 3;
                    highSeq = sndNxt;
                    tlpHighSeq = 0;
                    congestionState = CongestionState.RECOVERY;
                    caIncrCounter = 0;
                    // 对齐 Linux tcp_enter_recovery → tcp_mark_head_lost:NewReno 无 SACK
                    // 信息驱动 LOST 标记,进入 Fast Retransmit 前先把队首段记为 LOST,
                    // 这样 tcp_retransmit_skb 的 LOST 优先路径能与 RACK 场景保持一致。
                    TcpIncomingAckHandler.tcp_mark_head_lost(this, 1);
                    TcpRetransmitter.INSTANCE.retransmit(this);
                } else if (congestionState == CongestionState.RECOVERY) {
                    cwnd++;
                }
                return;
            }

            if (congestionState == CongestionState.RECOVERY && after(sndUna, highSeq)) {
                cwnd = ssthresh;
                congestionState = CongestionState.OPEN;
                caIncrCounter = 0;
            } else if (congestionState == CongestionState.LOSS) {
                congestionState = CongestionState.OPEN;
                caIncrCounter = 0;
            }

            dupacks = 0;
            if (cwnd < ssthresh) {
                cwnd += newlyAcked;
            } else {
                caIncrCounter += newlyAcked;
                if (caIncrCounter >= cwnd) {
                    cwnd++;
                    caIncrCounter = 0;
                }
            }
        }

        public void onTimeoutByCc() {
            ssthresh = Math.max(cwnd / 2, 2);
            cwnd = 1;
            dupacks = 0;
            caIncrCounter = 0;
            tlpHighSeq = 0;
            congestionState = CongestionState.LOSS;
        }

        public int tlpHighSeq() {
            return tlpHighSeq;
        }

        public void tlpHighSeq(int tlpHighSeq) {
            this.tlpHighSeq = tlpHighSeq;
        }

        public int cwnd() {
            return cwnd;
        }

        public void cwnd(int cwnd) {
            this.cwnd = Math.max(cwnd, 1);
        }

        public int ssthresh() {
            return ssthresh;
        }

        public void ssthresh(int ssthresh) {
            this.ssthresh = Math.max(ssthresh, 2);
        }

        public CongestionState congestionState() {
            return congestionState;
        }

        public long sndCwndStampMs() {
            return sndCwndStampMs;
        }

        public void sndCwndStampMs(long sndCwndStampMs) {
            this.sndCwndStampMs = sndCwndStampMs;
        }

        public int sndCwndUsed() {
            return sndCwndUsed;
        }

        public void sndCwndUsed(int sndCwndUsed) {
            this.sndCwndUsed = Math.max(sndCwndUsed, 0);
        }

        public boolean isCwndLimited() {
            return isCwndLimited;
        }

        public void isCwndLimited(boolean isCwndLimited) {
            this.isCwndLimited = isCwndLimited;
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
            if (packetsOut != 0) {
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
            int curCwnd = cwnd;
            // 近似 Linux tcp_current_ssthresh():CA_Open 下 max(ssthresh, 3*cwnd/4 + 1)
            if (congestionState == CongestionState.OPEN) {
                int conservative = (curCwnd * 3 / 4) + 1;
                if (ssthresh < conservative) {
                    ssthresh = conservative;
                }
            }
            restartCwnd = Math.min(restartCwnd, curCwnd);
            long remain = delta;
            while ((remain -= rtoMs) > 0 && curCwnd > restartCwnd) {
                curCwnd >>= 1;
            }
            cwnd = Math.max(curCwnd, restartCwnd);
            sndCwndStampMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
            sndCwndUsed = 0;
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
                this.isCwndLimited = true;
                this.sndCwndStampMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
            } else {
                // 应用欠载:若上一窗口 cwnd 曾经受限,等待 1 RTO 后做收敛。
                if (this.isCwndLimited) {
                    long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
                    long elapsed = now - this.sndCwndStampMs;
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
                int winUsed = Math.max(sndCwndUsed, initWin);
                if (winUsed < cwnd) {
                    // ssthresh = tcp_current_ssthresh() — CA_Open 时为 max(ssthresh, 3*cwnd/4+1)
                    int conservative = (cwnd * 3 / 4) + 1;
                    if (ssthresh < conservative) {
                        ssthresh = conservative;
                    }
                    cwnd = (cwnd + winUsed) >> 1;
                }
                sndCwndUsed = 0;
            }
            sndCwndStampMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
            isCwndLimited = false;
        }

        /**
         * 单次发送后更新 {@code snd_cwnd_used} 高水位 — 对齐 Linux
         * {@code tcp_event_new_data_sent} 内的 {@code snd_cwnd_used} 追踪逻辑。
         * 必须在 {@code packetsOut++} 之后调用。
         */
        public void onDataSentUpdateCwndUsed() {
            if (sndCwndUsed < packetsOut) {
                sndCwndUsed = packetsOut;
                sndCwndStampMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
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

    protected static final class tcp_request_sock extends SockCommon {
        private final TcpSock listener;
        private final TcpHandshaker request;
        private ChannelFuture connectFuture;
        private Channel childChannel;
        private ChannelFutureListener handshakeCloseListener;
        private TcpPacketBuf synPacket;

        // ── request_sock (Linux include/net/request_sock.h) ────────────────
        /** Request 定时器槽 — 对应 {@code request_sock.rsk_timer}。 */
        public volatile Runnable rsk_timer;
        /** 绝对到期时间戳 (ms) — 对应 {@code request_sock.timeout}。 */
        public long timeout;

        // ── inet_request_sock (include/net/inet_sock.h) ────────────────────
        /** MSS 协商结果 — 对应 {@code inet_request_sock.mss}。 */
        public int mss;
        /** 对端接收窗口缩放因子 — 对应 {@code inet_request_sock.snd_wscale}。 */
        public int snd_wscale;
        /** 本端接收窗口缩放因子 — 对应 {@code inet_request_sock.rcv_wscale}。 */
        public int rcv_wscale;
        /** Window Scaling 是否协商成功。 */
        public boolean wscale_ok;
        /** Timestamps 是否协商成功。 */
        public boolean tstamp_ok;

        // ── tcp_request_sock (include/linux/tcp.h) ─────────────────────────
        /** 微秒级时间戳协商标志 — 对应 {@code tcp_request_sock.req_usec_ts}。 */
        public boolean req_usec_ts;
        /** 客户端 ISN — 对应 {@code tcp_request_sock.rcv_isn}。 */
        public int rcv_isn;
        /** 服务端 ISN — 对应 {@code tcp_request_sock.snt_isn}。 */
        public int snt_isn;
        /** Timestamps 随机偏移,防止 TSval 泄露启动时间 — 对应 {@code ts_off}。 */
        public long ts_off;
        /** 第一次发送 SYN-ACK 时携带的 TSval — 对应 {@code snt_tsval_first}。 */
        public int snt_tsval_first;
        /** 最近一次发送 SYN-ACK 时携带的 TSval — 对应 {@code snt_tsval_last}。 */
        public int snt_tsval_last;
        /** 最近一次 OOW 挑战 ACK 时间戳 — 对应 {@code last_oow_ack_time}。 */
        public int last_oow_ack_time;
        /** 已确认接收的下一个序号 — 对应 {@code tcp_request_sock.rcv_nxt}。 */
        public int rcv_nxt;
        /** SYN 报文中 TOS 字段 — 对应 {@code tcp_request_sock.syn_tos}。 */
        public int syn_tos;
        /** 来自 SYN 的初始发送窗口 — 对应 {@code tcp_request_sock.snd_wnd}。 */
        public int snd_wnd;
        /** SYN-ACK 已重传次数 (0 = 仅首发) — 对应 {@code tcp_request_sock.num_retrans}。 */
        public int num_retrans;
        /** 对端 SYN 中 TSval,用于初始化子 socket 的 ts_recent — 对应 {@code ts_recent}。 */
        public long ts_recent;

        protected tcp_request_sock(FourTuple key, TcpSock listener, TcpHandshaker request) {
            super(key);
            this.listener = listener;
            this.request = request;
        }

        public TcpSock listener() {
            return listener;
        }

        public TcpHandshaker request() {
            return request;
        }

        public ChannelFuture connectFuture() {
            return connectFuture;
        }

        public void connectFuture(ChannelFuture connectFuture) {
            this.connectFuture = connectFuture;
        }

        public Channel childChannel() {
            return childChannel;
        }

        public void childChannel(Channel childChannel) {
            this.childChannel = childChannel;
        }

        public ChannelFutureListener handshakeCloseListener() {
            return handshakeCloseListener;
        }

        public void handshakeCloseListener(ChannelFutureListener handshakeCloseListener) {
            this.handshakeCloseListener = handshakeCloseListener;
        }

        public TcpPacketBuf synPacket() {
            return synPacket;
        }

        public void synPacket(TcpPacketBuf synPacket) {
            this.synPacket = synPacket;
        }

        @Override
        public TcpConnectionState state() {
            return TcpConnectionState.TCP_SYN_RECV;
        }

        @Override
        public void state(TcpConnectionState state) {
        }
    }
}
