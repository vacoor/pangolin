package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单元测试 — 验证 {@link PacingTokenBucket} 的 token 累积 / 消费 / cap / nano-wait 计算。
 *
 * <p>{@code rateBps == 0} 不在本类 cover(调用方 {@code TcpOutput.pacingCheck} 短路);
 * 这里只测 {@code rateBps > 0} 的几种典型场景。
 */
class PacingTokenBucketTest {

    @Test
    @DisplayName("首次 tryConsume 不阻塞(burst 1 段)")
    void firstConsumeNeverBlocks() {
        PacingTokenBucket b = new PacingTokenBucket();
        // 1 Mbps = 125_000 字节/秒
        boolean ok = b.tryConsume(125_000L, 1500, 1_000_000L);
        assertThat(ok).as("首次发送应允许").isTrue();
    }

    @Test
    @DisplayName("token 累积速率正确:1 秒后能发完 rate 字节")
    void tokensAccumulateAtRate() {
        PacingTokenBucket b = new PacingTokenBucket();
        long rate = 100_000L;       // 100 KB/s
        long t0 = 1_000_000_000L;
        // 首段消费 1500
        b.tryConsume(rate, 1500, t0);
        // 跳过 1ms — 应累积 100 字节
        // 然后再发 1500 字节 -> 不够,要更多时间
        boolean ok = b.tryConsume(rate, 1500, t0 + 1_000_000L);
        assertThat(ok).as("1ms 后 100 字节累积,1500 字节段不够").isFalse();
        // 再跳 200ms — 累积大约 20000 字节,足够 1500
        ok = b.tryConsume(rate, 1500, t0 + 1_000_000L + 200_000_000L);
        assertThat(ok).as("200ms 后累积充足").isTrue();
    }

    @Test
    @DisplayName("token cap 在 1ms × rate(避免 idle 长期累积 burst,但不拦截单段最低需求)")
    void tokensCappedAtOneMillisRate() {
        // 用 segBytes < rate*1ms 的小段来验证 cap 真正生效
        PacingTokenBucket b = new PacingTokenBucket();
        long rate = 10_000L;            // 10 KB/s,1ms cap = 10 字节
        long t0 = 1_000_000_000L;
        // 首段 5 字节消费完,tokens=0
        b.tryConsume(rate, 5, t0);
        // 跳 1 秒 — 理论累积 10000 字节,cap=max(10, 5)=10 → tokens=10
        long t1 = t0 + 1_000_000_000L;
        // 100 字节段不够(超出 cap=max(10, 100)=100,但 tokens 在累加阶段被 cap 到 10)
        // 重要:tokens 累积 (1s * 10000/s) = 10000,然后 cap = max(rate/1000, segBytes) = 100
        //       由于 segBytes 撑大 cap,tokens 会被 clamp 到 100,正好够。
        // 这反映"cap 至少够 1 段"是有意的设计(避免单段被 cap 卡死)。
        // 真正能验证 cap 限制 burst 的场景:idle 后段 ≤ cap_base(rate*1ms)。
        boolean ok = b.tryConsume(rate, 8, t1);
        assertThat(ok).as("8 字节 ≤ rate×1ms=10,被 cap 后 tokens=10 仍够").isTrue();
        // 但累积的"额外 burst"被吃掉:再来一段 8 字节,tokens=2 不够
        ok = b.tryConsume(rate, 8, t1);
        assertThat(ok).as("第二段 8 字节,tokens=2 不够,需等更多累积").isFalse();
    }

    @Test
    @DisplayName("nanosUntilNextSend 反映等待时长")
    void nanosUntilNextSendCorrect() {
        PacingTokenBucket b = new PacingTokenBucket();
        long rate = 1_000_000L;     // 1 MB/s
        long t0 = 1_000_000_000L;
        b.tryConsume(rate, 1500, t0);   // 消费 1500
        // 此时 token 应该接近 0,需要 1500 字节
        long wait = b.nanosUntilNextSend(rate, 1500);
        // 1500 字节 / (1 MB/s) ≈ 1.5 ms = 1_500_000 ns(允许 2× 容差)
        assertThat(wait).as("1500B @ 1MB/s 约 1.5ms").isBetween(1_000_000L, 3_000_000L);
    }

    @Test
    @DisplayName("reset 清空 token + lastNs")
    void resetClearsState() {
        PacingTokenBucket b = new PacingTokenBucket();
        b.tryConsume(1_000_000L, 1500, 1_000_000L);
        b.reset();
        // reset 后 lastNs=0,首次 tryConsume 同 first-call 行为(允许)
        boolean ok = b.tryConsume(1_000_000L, 1500, 2_000_000L);
        assertThat(ok).isTrue();
    }
}
