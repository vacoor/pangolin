package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.SHUTDOWN_MASK;

/**
 * TCP 栈的顶层容器(R4.2b-2)—— 持有注册表、per-stack 全局组件和生命周期 API。
 *
 * <p>对齐 Linux {@code struct inet_hashinfo} + 部分 {@code tcp_prot} 静态状态;
 * 对齐 gVisor {@code pkg/tcpip/stack.Stack} 的 transport-agnostic 容器。
 *
 * <p><b>职责</b>:
 * <ul>
 *   <li>注册表:{@link #establishedRegistry} / {@link #timewaitRegistry} /
 *       {@link #listener}(LISTEN 聚合)/ {@link #lookup}(查表函数);</li>
 *   <li>per-stack 全局组件:{@link #mib} / {@link #output} / {@link #retransmitter} /
 *       {@link #handshakerFactory} / {@link #initializer} / {@link #config} /
 *       {@link #tcpGroup};</li>
 *   <li>生命周期:{@code tcpDone} / {@code inet_csk_destroy_sock} /
 *       {@code closeState} / {@code timeWait} / {@code inet_twsk_kill} /
 *       {@code inet_twsk_reschedule}。</li>
 * </ul>
 *
 * <p><b>不含</b>:入站包路由(由 {@code SegmentDispatcher} 承担,R4.2b-3)、FSM
 * 逻辑(由 {@code Sender / Receiver / Listener / TcpTimewaitSock} 承担,R4.2b-4)、
 * per-sock 装配({@code configure} 留在 {@link SegmentDispatcher},依赖 {@code sk.stack(this)}
 * 的 SegmentDispatcher 类型)。
 *
 * <p><b>线程模型</b>:
 * <ul>
 *   <li>established / timewait registry 读写跨 EL(TUN EL + sock EL),用
 *       {@link ConcurrentHashMap};</li>
 *   <li>listener.synRegistry 只在 TUN EL 访问,无需 CAS;</li>
 *   <li>生命周期方法(tcpDone / timeWait 等)在 sock 专属 EL 或 TUN EL 上调用,
 *       内部通过 registry 的 CHM 和 sock.eventLoop() 调度完成线程归属。</li>
 * </ul>
 *
 * <p><b>R4.2 进度</b>:
 * <ul>
 *   <li>R4.2b-2 后 {@link SegmentDispatcher} 已 {@code extends TcpStack},生命周期字段/方法上移至此 ✅</li>
 *   <li>R4.2b-3 重命名完成({@code TcpMultiplexer} → {@code SegmentDispatcher})✅</li>
 *   <li>R4.2b-4 FSM 方法下沉到 Sender / Receiver / Listener / TcpTimewaitSock ✅</li>
 *   <li>未来(可选):{@code SegmentDispatcher} 改 has-a 组合 {@link TcpStack},目前仍继承。</li>
 * </ul>
 */
public class TcpStack {

    public static final int DEFAULT_MAX_SYN_BACKLOG = 1024;

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

    /** 返回墙钟秒数(对应 Linux {@code get_seconds()},用于 {@code ts_recent_stamp})。 */
    static int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    protected final TcpConfig config;
    protected final TcpHandshakerFactory handshakerFactory;
    protected final TcpRetransmitter retransmitter = new TcpRetransmitter();
    protected final TcpMibStats mib = new TcpMibStats();
    protected final TcpOutput output = new TcpOutput();
    protected final EventLoopGroup tcpGroup;
    protected final TcpSockInitializer initializer;
    protected final Map<FourTuple, TcpSock> establishedRegistry;
    protected final Map<FourTuple, TcpTimewaitSock> timewaitRegistry;

    /**
     * LISTEN 端聚合(R4.1):holds listenSock + synRegistry + maxSynBacklog。
     * 创建在子类 {@code SegmentDispatcher.init()} 里(listenSock 先创建并 configure),非 final。
     */
    protected Listener listener;

    /**
     * 入站段四元组 → sock 的纯查表组件(R4.2b-1)。在 listener 创建完之后实例化。
     */
    protected SockLookup lookup;

    protected TcpStack(TcpConfig config, EventLoopGroup tcpGroup, TcpSockInitializer initializer) {
        this.config = config;
        this.handshakerFactory = new TcpHandshakerFactory(config, output);
        this.tcpGroup = tcpGroup;
        this.initializer = Objects.requireNonNull(initializer, "initializer");
        this.establishedRegistry = new ConcurrentHashMap<>();
        this.timewaitRegistry = new ConcurrentHashMap<>();
        // NOTE: listener / lookup 由子类 init() 在 super(...) 返回后初始化
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 公开访问器
    // ═══════════════════════════════════════════════════════════════════════

    public Listener listener() {
        return listener;
    }

    public TcpRetransmitter retransmitter() {
        return retransmitter;
    }

    public TcpMibStats mib() {
        return mib;
    }

    public TcpOutput output() {
        return output;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 生命周期 API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 半连接 → ESTABLISHED 的注册表迁移(R4.2b-4a 从 {@code SegmentDispatcher} 迁入)。
     * 对齐 Linux {@code inet_csk_complete_hashdance}:从 request_sock 哈希摘出、
     * 释放 SYN 包 retain,插入 established 哈希(v2 的 {@link #establishedRegistry})。
     * sender/receiver/stack 已在 {@code tcp_v4_syn_recv_sock} 的 {@code init(newsk)}
     * 里 configure。
     */
    public void moveToEstablished(TcpRequestSock req, TcpSock sock) {
        if (req.synPacket() != null) {
            req.synPacket().release();
            req.synPacket(null);
        }
        listener.removeRequest(req);
        establishedRegistry.put(sock.fourTuple(), sock);
    }

    public void tcpDone(TcpSock tp) {
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

    public void inet_csk_destroy_sock(TcpRequestSock req) {
        // P2.1:销毁前让 initializer 释放 attachment 资源(如 backend state)
        try {
            initializer.onRequestDestroyed(req);
        } catch (Throwable ignore) {
            // 保护:用户 initializer 异常不阻塞 req 销毁
        }
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
        listener.removeRequest(req);
    }

    public boolean closeState(TcpSock sk) {
        if (!sk.hasConnection()) {
            return false;
        }
        int next = NEW_STATE[sk.state().ordinal() + 1];
        int ns = next & TCP_STATE_MASK;
        sk.state(TcpConnectionState.values()[ns]);
        return 0 != (next & TCP_ACTION_FIN);
    }

    public void timeWait(ChannelHandlerContext ctx, TcpSock tp, TcpConnectionState state) {
        timeWait(tp, state, config.timeWaitMs());
    }

    /**
     * 对齐 Linux {@code timeWait}(net/ipv4/tcp_minisocks.c):
     * 从重量级 {@link TcpSock} 中摘出 TIME_WAIT 阶段所需的最小快照构建 {@link TcpTimewaitSock},
     * 注册到 {@link #timewaitRegistry} 并安排 2MSL 定时器;原 {@link TcpSock} 立即销毁,
     * 释放发送 / 接收缓冲、取消所有定时器,腾出 {@link #establishedRegistry} 中的槽位。
     *
     * <p>{@code state} 参数对齐 Linux {@code timeWait(sk, state, timeo)} 用于
     * 区分 FIN_WAIT_2 / TIME_WAIT 子状态 — 值会写入
     * {@link TcpTimewaitSock#tw_substate},由 {@code timewaitStateProcess}
     * 根据该字段分派(FIN_WAIT_2 等对端 FIN,TIME_WAIT 静默重放 ACK)。二者到期行为
     * 均为 {@link #inet_twsk_kill}。
     *
     * <p>迟到段重放 FIN-ACK 的通路由 {@code timewaitStateProcess} +
     * {@code TcpOutput.timewaitSendAck} 承担,共享 TUN 侧 channel,无需 {@link TcpSock}。
     */
    public void timeWait(TcpSock tp, TcpConnectionState state, long timeoutMs) {
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
        // 对齐 Linux timeWait(sk, state, timeo):state ∈ {FIN_WAIT_2, TIME_WAIT}
        tw.tw_substate = (state == TcpConnectionState.FIN_WAIT_2)
                ? TcpConnectionState.FIN_WAIT_2
                : TcpConnectionState.TIME_WAIT;

        final long delay = Math.max(timeoutMs, 1L);
        tw.tw_timeout = System.currentTimeMillis() + delay;
        timewaitRegistry.put(ft, tw);
        mib.inc(TcpMib.TCPTIMEWAITCREATED);

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
        ScheduledFuture<?> f = tw.tw_timer;
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
        ScheduledFuture<?> prev = tw.tw_timer;
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
}
