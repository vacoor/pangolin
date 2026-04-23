package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 轻量 MIB 计数器集 — 对齐 Linux {@code struct linux_mib} + {@code skb_drop_reason}
 * 轨迹,v2 以进程内单例 + {@link AtomicLongArray} 无锁累加实现。
 *
 * <p>计数来源:
 * <ul>
 *   <li>{@link TcpMib} 家族:由 {@link #inc(TcpMib)} 投递,ordinal 作为下标。</li>
 *   <li>{@link SkbDropReason} 家族:由 {@link #incDrop(int)} 投递,code 作为下标。</li>
 * </ul>
 *
 * <p>当前 v2 不引入外部监控(Prometheus / micrometer)导出,{@link #snapshotMib()} /
 * {@link #snapshotDrop()} 提供只读快照供运维抓取。
 */
public final class TcpMibStats {

    /**
     * 进程级 fallback 实例。R1.2(2026-04-23):SegmentDispatcher 已降为 per-stack
     * 持有独立 {@code mib} 字段;本 INSTANCE 仅保留给少量未持栈引用的叶子对象
     * (如 TcpReceiveBuffer 的 OFO 相关计数),将在 R3 抽 Receiver 时收尾清理。
     */
    public static final TcpMibStats INSTANCE = new TcpMibStats();

    /**
     * {@link SkbDropReason} 的 int code 上限;当前最大条目为
     * {@code SKB_DROP_REASON_TCP_INVALID_SYN=46},保留余量到 128 便于未来追加。
     */
    private static final int DROP_REASON_SLOTS = 128;

    private final AtomicLongArray mibCounters = new AtomicLongArray(TcpMib.values().length);
    private final AtomicLongArray dropReasonCounters = new AtomicLongArray(DROP_REASON_SLOTS);

    public TcpMibStats() {
    }

    /** MIB 计数器 +1 — 对应 Linux {@code NET_INC_STATS(net, mib)}。 */
    public void inc(TcpMib mib) {
        if (mib == null) return;
        mibCounters.incrementAndGet(mib.ordinal());
    }

    /** MIB 计数器 +delta — 对应 Linux {@code NET_ADD_STATS(net, mib, val)}。 */
    public void add(TcpMib mib, long delta) {
        if (mib == null || delta == 0L) return;
        mibCounters.addAndGet(mib.ordinal(), delta);
    }

    /**
     * skb_drop_reason 计数器 +1 — 对应 Linux
     * {@code kfree_skb_reason(skb, reason)} 的 trace 投递。
     *
     * <p>{@code reason == 0} ({@code SKB_NOT_DROPPED_YET}) 时跳过,不计数未丢弃分支。
     */
    public void incDrop(int reason) {
        if (reason <= 0 || reason >= DROP_REASON_SLOTS) {
            return;
        }
        dropReasonCounters.incrementAndGet(reason);
    }

    /** 获取 MIB 单项当前值 — 用于单元测试 / 运维快照。 */
    public long get(TcpMib mib) {
        return mib == null ? 0L : mibCounters.get(mib.ordinal());
    }

    /** 获取 drop_reason 单项当前值。 */
    public long getDrop(int reason) {
        if (reason <= 0 || reason >= DROP_REASON_SLOTS) {
            return 0L;
        }
        return dropReasonCounters.get(reason);
    }

    /** MIB 全量快照,只读;值为抓取瞬间的精确计数。 */
    public Map<TcpMib, Long> snapshotMib() {
        Map<TcpMib, Long> out = new EnumMap<>(TcpMib.class);
        for (TcpMib m : TcpMib.values()) {
            long v = mibCounters.get(m.ordinal());
            if (v != 0L) {
                out.put(m, v);
            }
        }
        return out;
    }

    /** drop_reason 全量快照,键为 {@code SKB_DROP_REASON_*} int code。 */
    public long[] snapshotDrop() {
        long[] out = new long[DROP_REASON_SLOTS];
        for (int i = 0; i < DROP_REASON_SLOTS; i++) {
            out[i] = dropReasonCounters.get(i);
        }
        return out;
    }

    /** 测试 / 运维复位 — 所有计数归零。 */
    public void reset() {
        for (int i = 0; i < mibCounters.length(); i++) {
            mibCounters.set(i, 0L);
        }
        for (int i = 0; i < DROP_REASON_SLOTS; i++) {
            dropReasonCounters.set(i, 0L);
        }
    }
}
