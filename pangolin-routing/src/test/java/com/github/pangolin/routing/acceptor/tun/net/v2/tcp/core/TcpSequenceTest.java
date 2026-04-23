package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 序列号 32-bit 模运算比较。锚点:Linux {@code include/linux/tcp.h} 的
 * {@code before} / {@code after} / {@code between}。
 *
 * <p>核心覆盖:小值正常比较、wrap-around(跨 2^32)、相等边界。
 */
class TcpSequenceTest {

    @Nested
    @DisplayName("after(seq2, seq1) — seq2 严格在 seq1 之后")
    class AfterTests {

        @Test
        void smallAscending() {
            assertThat(TcpSequence.after(5, 4)).isTrue();
            assertThat(TcpSequence.after(4, 5)).isFalse();
        }

        @Test
        void equalIsNotAfter() {
            assertThat(TcpSequence.after(4, 4)).isFalse();
        }

        @Test
        void wrapAround() {
            // 0 在 2^32-2 之后(环形空间上 0 = 2^32 = 2^32-2 + 2)
            assertThat(TcpSequence.after(0, 0xFFFFFFFE)).isTrue();
            // 2^32-2 不在 0 之后
            assertThat(TcpSequence.after(0xFFFFFFFE, 0)).isFalse();
        }
    }

    @Nested
    @DisplayName("before(seq1, seq2) — seq1 严格在 seq2 之前")
    class BeforeTests {

        @Test
        void smallAscending() {
            assertThat(TcpSequence.before(4, 5)).isTrue();
            assertThat(TcpSequence.before(5, 4)).isFalse();
        }

        @Test
        void equalIsNotBefore() {
            assertThat(TcpSequence.before(4, 4)).isFalse();
        }

        @Test
        void wrapAround() {
            assertThat(TcpSequence.before(0xFFFFFFFE, 0)).isTrue();
            assertThat(TcpSequence.before(0, 0xFFFFFFFE)).isFalse();
        }
    }

    @Nested
    @DisplayName("between(seq1, x, seq2) — seq1 ≤ x ≤ seq2")
    class BetweenTests {

        @Test
        void inRange() {
            assertThat(TcpSequence.between(1, 2, 3)).isTrue();
            assertThat(TcpSequence.between(1, 1, 3)).isTrue();   // 左闭
            assertThat(TcpSequence.between(1, 3, 3)).isTrue();   // 右闭
        }

        @Test
        void outOfRange() {
            assertThat(TcpSequence.between(1, 5, 3)).isFalse();
            assertThat(TcpSequence.between(1, 0, 3)).isFalse();
        }

        @Test
        void wrapAround() {
            // 窗口跨越 2^32:seq1=2^32-2, x=0, seq2=2
            assertThat(TcpSequence.between(0xFFFFFFFE, 0, 2)).isTrue();
            assertThat(TcpSequence.between(0xFFFFFFFE, 0xFFFFFFFF, 2)).isTrue();
            // x 落在窗口外(环形距离看)
            assertThat(TcpSequence.between(0xFFFFFFFE, 10, 2)).isFalse();
        }
    }
}
