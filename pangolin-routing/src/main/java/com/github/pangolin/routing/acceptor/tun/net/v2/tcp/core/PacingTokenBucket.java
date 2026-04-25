package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * Pacing token bucket — sender 主路径在每段发送前消费 token,token 不足则推迟到下一
 * EventLoop tick。仅当 {@code pacingRateBps > 0} 时生效;为 0 时调用方需短路跳过,
 * 本类不做该决策(避免 hot path 多余分支)。
 *
 * <p>对齐 Linux {@code tcp_pacing_check} / {@code sk->sk_pacing_rate} 的语义:基于
 * {@code nowNs} 与上一次发送时戳的差值累积 token(字节),消费段长度。token 上限
 * cap 为 {@code rate * 1ms}(避免 idle 长时间累积 burst)。
 *
 * <p>线程模型:per-{@code Sender} 实例,EventLoop 内单线程访问,无锁。
 */
public final class PacingTokenBucket {

    /** 上一次 token 累积的纳秒时戳。 */
    private long lastNs;
    /** 当前可用 token(字节)。 */
    private long tokens;

    /**
     * 尝试消费 {@code segBytes} 字节的 token。先按 {@code (nowNs - lastNs) * rateBps / 1e9}
     * 累积 token,然后判断是否够本段。
     *
     * @param rateBps    pacing rate(字节/秒),调用方保证 {@code > 0}
     * @param segBytes   本段大小(字节)
     * @param nowNs      当前 nano time(通常 {@code System.nanoTime()})
     * @return {@code true} 表示 token 充足、可发送;{@code false} 表示需要推迟
     */
    public boolean tryConsume(long rateBps, int segBytes, long nowNs) {
        if (lastNs == 0L) {
            lastNs = nowNs;
            tokens = segBytes;          // 首次发送不阻塞,burst 1 段
        } else {
            long deltaNs = nowNs - lastNs;
            if (deltaNs > 0L) {
                long add = (deltaNs * rateBps) / 1_000_000_000L;
                tokens += add;
                lastNs = nowNs;
            }
            // cap 上限:1ms 的 rate 配额,避免长 idle 后无限累积造成 burst
            long cap = rateBps / 1000L;
            if (cap < segBytes) cap = segBytes;
            if (tokens > cap) tokens = cap;
        }
        if (tokens >= segBytes) {
            tokens -= segBytes;
            return true;
        }
        return false;
    }

    /**
     * 计算下次能发送 {@code segBytes} 字节所需等待的纳秒数。调用方据此 schedule 下次
     * writeXmit 唤醒。
     */
    public long nanosUntilNextSend(long rateBps, int segBytes) {
        if (rateBps <= 0L) return 0L;
        long shortfall = segBytes - tokens;
        if (shortfall <= 0L) return 0L;
        return (shortfall * 1_000_000_000L) / rateBps;
    }

    /** 重置桶状态(连接重建 / RTO 清零等场景)。 */
    public void reset() {
        lastNs = 0L;
        tokens = 0L;
    }
}
