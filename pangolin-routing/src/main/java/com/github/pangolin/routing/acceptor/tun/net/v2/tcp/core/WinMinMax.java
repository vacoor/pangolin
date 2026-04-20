package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * O(1) Kathleen Nichols "Windowed Min" filter — 对齐 Linux
 * {@code struct minmax} (include/linux/win_minmax.h, lib/win_minmax.c),
 * 以三段子窗口近似精确 running min。v2 用来承载 {@code tp->rtt_min} /
 * {@code tp->tcp_mstamp} 两元组,供 RACK `reo_wnd = min(min_rtt × steps >> 2,
 * srtt >> 3)` 估计与 BBR 等后续模块使用。
 *
 * <p>三元组 {@code s[0/1/2]} 分别代表当前窗口、下 1/2 窗口、下 1/4 窗口内的候选最小值;
 * 新样本按 Linux {@code minmax_running_min} / {@code minmax_subwin_update} 的分支
 * 做淘汰 + 替换,保证 {@code s[0].v} 始终是时间窗内的 running min。
 *
 * <p>时间坐标由调用方选定(Linux 用 {@code tcp_jiffies32} 毫秒;v2 与
 * {@link com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock#tcp_jiffies32()}
 * 保持一致),窗口长度 {@code win} 与 {@code t} 取同一单位。值 {@code v} 使用
 * {@code int}(对齐 Linux {@code u32});v2 约定以微秒存 RTT,超出 {@code int} 的
 * 极端值由调用方在入口 clamp(2.1s 对应的微秒已接近 int 半区,足够覆盖真实 RTT)。
 */
public final class WinMinMax {

    private int t0, t1, t2;
    private int v0, v1, v2;

    /** 构造初态:无样本,消费者应以 {@code v0 == sentinel} 判定未初始化。 */
    public WinMinMax() {
        this(TcpConstants.TCP_MIN_RTT_NO_SAMPLE);
    }

    public WinMinMax(int sentinel) {
        reset(0, sentinel);
    }

    /** 当前 running min 值(无样本时为构造 sentinel)。 */
    public int min() {
        return v0;
    }

    /** 当前 running min 的采样时戳(与 {@code update} 传入的 {@code t} 同基)。 */
    public int minTimestamp() {
        return t0;
    }

    /** 强制重置为 {@code (t, v)} — 对齐 Linux {@code minmax_reset}。 */
    public void reset(int t, int v) {
        this.t0 = this.t1 = this.t2 = t;
        this.v0 = this.v1 = this.v2 = v;
    }

    /**
     * 喂入一个新样本;返回更新后的 running min。
     * 等价 Linux {@code minmax_running_min(m, win, t, meas)}。
     *
     * @param win 时间窗长度(与 {@code t} 同单位,Linux 典型 {@code sysctl_tcp_min_rtt_wlen * HZ})
     * @param t   当前时戳
     * @param v   当前测量值
     * @return 窗内 running min
     */
    public int update(int win, int t, int v) {
        // 新样本 ≤ s[0].v 或 s[2] 已过窗 → 整体重置
        if (v <= v0 || sub(t, t2) > win) {
            reset(t, v);
            return v0;
        }
        // 更新 s[1] / s[2]:新值小于等于它们时同步下移
        if (v <= v1) {
            v1 = v;  t1 = t;
            v2 = v;  t2 = t;
        } else if (v <= v2) {
            v2 = v;  t2 = t;
        }
        return subwinUpdate(win, t, v);
    }

    /** 对齐 Linux {@code minmax_subwin_update}。 */
    private int subwinUpdate(int win, int t, int v) {
        final int dt = sub(t, t0);
        if (dt > win) {
            // 整窗内无更小样本 → 晋升 s[1] 为新 s[0]
            t0 = t1;  v0 = v1;
            t1 = t2;  v1 = v2;
            t2 = t;   v2 = v;
            if (sub(t, t0) > win) {
                t0 = t1;  v0 = v1;
                t1 = t2;  v1 = v2;
                t2 = t;   v2 = v;
            }
        } else if (t1 == t0 && dt > (win >> 2)) {
            // 已走过 1/4 窗仍无 s[1] 更新 → 从第二个 1/4 窗取 2nd 候选
            t1 = t;  v1 = v;
            t2 = t;  v2 = v;
        } else if (t2 == t1 && dt > (win >> 1)) {
            // 已走过 1/2 窗仍无 s[2] 更新 → 从后半窗取 3rd 候选
            t2 = t;  v2 = v;
        }
        return v0;
    }

    /** u32 回绕安全的减法:{@code a - b},以 int 二补码保留回绕语义。 */
    private static int sub(int a, int b) {
        return a - b;
    }
}
