package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import io.netty.channel.Channel;

import java.util.concurrent.ScheduledFuture;

/**
 * TIME_WAIT 套接字最小化状态结构 — 对齐 Linux 内核继承链
 * {@code inet_timewait_sock} → {@code tcp_timewait_sock}
 * (include/net/inet_timewait_sock.h, include/linux/tcp.h)。
 *
 * <p>v2 因 A10/A11 保留组织差异,将 {@code inet_timewait_sock} 与 {@code tcp_timewait_sock}
 * 合并为单类,字段命名与 Linux 源码保持一致,便于逐行对照;同时继承
 * {@link SockCommon},使 {@code __inet_lookup_skb} 在 ESTABLISHED miss 时能
 * 返回 {@code TcpTimewaitSock} 进入 {@code timewaitStateProcess} 路径。
 *
 * <p>仅保留 TIME_WAIT 阶段所必须的上下文:
 * <ul>
 *   <li>{@code tw_channel} — 用于从 {@code TcpOutput.timewaitSendAck} 重放 FIN-ACK 的
 *       TUN 侧 channel(共享,原 {@code TcpSock.channel()});</li>
 *   <li>{@code tw_rcv_nxt / tw_snd_nxt / tw_rcv_wnd / tw_rcv_wscale} — 迟到段的序号 / 窗口校验;</li>
 *   <li>PAWS 上下文 ({@code tw_ts_recent / tw_ts_recent_stamp / tw_ts_enabled})。</li>
 * </ul>
 *
 * <p>线程归属:创建与销毁均在原 {@code TcpSock} 所在的 EventLoop;2MSL 定时器亦派发至同一循环。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_timewait_sock.h">struct inet_timewait_sock</a>
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h">struct tcp_timewait_sock</a>
 */
public final class TcpTimewaitSock extends SockCommon {

    // ── 四元组 / 定时器 (inet_timewait_sock) ────────────────────────────────
    /**
     * 2MSL (TIME_WAIT) 定时器句柄 — 对应 Linux {@code inet_timewait_sock.tw_timer}。
     * 重放 FIN-ACK 时会 cancel 再 re-schedule,对应 Linux
     * {@code inet_twsk_reschedule}。
     */
    public volatile ScheduledFuture<?> tw_timer;
    /** 绝对到期时间戳 (ms),供定时器取消 / 重置判断。 */
    public long tw_timeout;

    // ── 出站 ACK / RST 所需 ──────────────────────────────────────────────────
    /** TUN 侧共享 channel — 用于迟到段重放 ACK / RST,引用仅供写出,不参与生命周期。 */
    public final Channel tw_channel;

    // ── TCP 状态快照 (tcp_timewait_sock) ────────────────────────────────────
    /**
     * 进入 TW bucket 时的 RCV.NXT — 识别迟到 FIN / 数据段是否在期望序号上。
     * 对应 Linux {@code tcp_timewait_sock.tw_rcv_nxt}。
     *
     * <p>FIN_WAIT_2 子状态收到期望序号 FIN 时推进 +1(消耗 FIN 的 SEQ 空间),
     * 与 Linux {@code timewaitStateProcess} 内的 {@code tw->tw_rcv_nxt++} 对齐。
     * TIME_WAIT 子状态保持不变。
     */
    public int tw_rcv_nxt;
    /**
     * 进入 TIME_WAIT 时的 SND.NXT — 重放 ACK / challenge ACK 的序号基线。
     * 对应 Linux {@code tcp_timewait_sock.tw_snd_nxt}。
     */
    public final int tw_snd_nxt;
    /** 进入 TIME_WAIT 时最近一次通告的接收窗口(未缩放),供迟到段窗口校验。 */
    public final int tw_rcv_wnd;
    /** 对端窗口缩放因子,便于未来窗口校验还原真实窗口。 */
    public final int tw_rcv_wscale;

    // ── Timestamps (PAWS) 相关 ──────────────────────────────────────────────
    /** Timestamps 是否协商成功 — 对应 Linux {@code tw_ts_recent_stamp != 0} 的惯用判断。 */
    public final boolean tw_ts_enabled;
    /** 进入 TIME_WAIT 时缓存的对端 TSval — 对应 {@code tw_ts_recent}。 */
    public long tw_ts_recent;
    /** 对应时间戳刷新时刻(秒) — 对应 {@code tw_ts_recent_stamp}。 */
    public int tw_ts_recent_stamp;

    // ── 状态字段 ─────────────────────────────────────────────────────────────
    /**
     * TW bucket 上的 TCP 子状态 — 对齐 Linux {@code inet_timewait_sock.tw_substate}
     * (include/net/inet_timewait_sock.h)。Linux 将 FIN_WAIT_2 与 TIME_WAIT 共用 mini-sock,
     * 由 {@code tw_substate} 区分:
     * <ul>
     *   <li>{@link TcpConnectionState#FIN_WAIT_2} — 本端已 FIN-ACKED,等待对端 FIN;
     *       收到对端 FIN 后 {@code tw_substate} 迁移为 TIME_WAIT 并启动 2MSL;</li>
     *   <li>{@link TcpConnectionState#TIME_WAIT} — 2MSL 静默等待,重放 ACK。</li>
     * </ul>
     *
     * <p>二者到期行为均为 {@link TcpMultiplexer#inet_twsk_kill(TcpTimewaitSock)},区别在
     * {@code timewaitStateProcess} 内的分支行为(FIN_WAIT_2 收到 FIN 需要推进 rcv_nxt
     * 并刷新 2MSL;TIME_WAIT 收到任何迟到段都仅重放 ACK)。
     */
    public TcpConnectionState tw_substate = TcpConnectionState.TIME_WAIT;

    public TcpTimewaitSock(FourTuple fourTuple,
                           Channel tw_channel,
                           int tw_rcv_nxt,
                           int tw_snd_nxt,
                           int tw_rcv_wnd,
                           int tw_rcv_wscale,
                           boolean tw_ts_enabled,
                           long tw_ts_recent,
                           int tw_ts_recent_stamp) {
        super(fourTuple);
        this.tw_channel        = tw_channel;
        this.tw_rcv_nxt        = tw_rcv_nxt;
        this.tw_snd_nxt        = tw_snd_nxt;
        this.tw_rcv_wnd        = tw_rcv_wnd;
        this.tw_rcv_wscale     = tw_rcv_wscale;
        this.tw_ts_enabled     = tw_ts_enabled;
        this.tw_ts_recent      = tw_ts_recent;
        this.tw_ts_recent_stamp = tw_ts_recent_stamp;
    }

    @Override
    public TcpConnectionState state() {
        return tw_substate;
    }

    @Override
    public void state(TcpConnectionState state) {
        this.tw_substate = state;
    }

    /**
     * 对齐 Linux {@code tcp_paws_reject} — 以 twsk 上缓存的 {@code tw_ts_recent} /
     * {@code tw_ts_recent_stamp} 判定入站 TSval 是否越界回退。
     *
     * <ul>
     *   <li>{@code tw_ts_recent - tsval <= TCP_PAWS_WINDOW} → 接受(含 1 tick 回绕容差);</li>
     *   <li>timestamps 未协商 / 基线为 0 → 接受;</li>
     *   <li>{@code tw_ts_recent_stamp} 距今超过 {@code TCP_PAWS_24DAYS} → 接受(陈旧基线覆盖);</li>
     *   <li>否则判定为 PAWS 拒绝。</li>
     * </ul>
     */
    public boolean pawsRejected(int tsval) {
        if (!tw_ts_enabled) {
            return false;
        }
        if (tw_ts_recent == 0L) {
            return false;
        }
        int delta = ((int) tw_ts_recent) - tsval;
        if (delta <= TcpConstants.TCP_PAWS_WINDOW) {
            return false;
        }
        if (tw_ts_recent_stamp != 0) {
            long ageSec = (long) ((int) (System.currentTimeMillis() / 1000L) - tw_ts_recent_stamp);
            if (ageSec >= TcpConstants.TCP_PAWS_24DAYS_SEC) {
                return false;
            }
        }
        return true;
    }

    /**
     * 合法段到达后刷新 {@code tw_ts_recent} / {@code tw_ts_recent_stamp},对齐 Linux
     * {@code tcp_store_ts_recent}。
     */
    public void updateTsRecent(int tsval) {
        if (!tw_ts_enabled) {
            return;
        }
        this.tw_ts_recent = tsval & 0xFFFFFFFFL;
        this.tw_ts_recent_stamp = (int) (System.currentTimeMillis() / 1000L);
    }
}
