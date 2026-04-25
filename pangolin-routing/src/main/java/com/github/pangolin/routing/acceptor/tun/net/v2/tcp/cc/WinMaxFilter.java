package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

/**
 * O(1) Kathleen Nichols Windowed-Max filter(long 版)— 对齐 Linux
 * {@code struct minmax} 的 max 形态(BBR 用作 BtlBw 滑窗最大值)。
 *
 * <p>v2 已有 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.WinMinMax}
 * 是 int + min 版(RACK reo_wnd 用),本类提供 long + max 版,专给 BBR BtlBw / 其他
 * 需要 long 精度的 max 滑窗使用。
 *
 * <p>三段子窗口算法等价 Linux {@code minmax_running_max}:{@code s[0/1/2]} 分别覆盖
 * 当前窗口、下 1/2 窗口、下 1/4 窗口内的最大候选;新样本按相同三段淘汰逻辑替换。
 *
 * <p>时间戳与窗口长度由调用方选定同一单位(BBR 用微秒)。
 */
public final class WinMaxFilter {

    private long t0, t1, t2;
    private long v0, v1, v2;

    public WinMaxFilter() {
        reset(0L, 0L);
    }

    /** 当前 running max(无样本时为构造时的 0)。 */
    public long max() {
        return v0;
    }

    /** running max 的采样时戳。 */
    public long maxTimestamp() {
        return t0;
    }

    /** 强制重置 — 对齐 Linux {@code minmax_reset}。 */
    public void reset(long t, long v) {
        this.t0 = this.t1 = this.t2 = t;
        this.v0 = this.v1 = this.v2 = v;
    }

    /**
     * 喂入新样本;返回更新后的 running max。
     *
     * @param win 时间窗长度(同 {@code t} 单位)
     * @param t   当前时戳
     * @param v   当前测量值
     */
    public long update(long win, long t, long v) {
        // 新样本 ≥ s[0].v 或 s[2] 已过窗 → 整体重置
        if (v >= v0 || (t - t2) > win) {
            reset(t, v);
            return v0;
        }
        if (v >= v1) {
            v1 = v; t1 = t;
            v2 = v; t2 = t;
        } else if (v >= v2) {
            v2 = v; t2 = t;
        }
        return subwinUpdate(win, t, v);
    }

    private long subwinUpdate(long win, long t, long v) {
        long dt = t - t0;
        if (dt > win) {
            // s[0] 已过窗 → 晋升 s[1] / s[2]
            t0 = t1; v0 = v1;
            t1 = t2; v1 = v2;
            t2 = t;  v2 = v;
            if ((t - t0) > win) {
                t0 = t1; v0 = v1;
                t1 = t2; v1 = v2;
                t2 = t;  v2 = v;
            }
        } else if (t1 == t0 && dt > (win >> 2)) {
            t1 = t; v1 = v;
            t2 = t; v2 = v;
        } else if (t2 == t1 && dt > (win >> 1)) {
            t2 = t; v2 = v;
        }
        return v0;
    }
}
