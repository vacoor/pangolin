package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单元测试 — 验证 {@link RateSample} 的字段默认值与 {@link RateSample#reset()}
 * 是否把所有字段(通用 + BBR 专用)都清零。
 */
class RateSampleTest {

    @Test
    @DisplayName("默认构造:所有字段为零值")
    void newInstanceAllZero() {
        RateSample rs = new RateSample();
        assertAllZero(rs);
    }

    @Test
    @DisplayName("reset 把已填充的字段清零")
    void resetClearsAllFields() {
        RateSample rs = new RateSample();
        // 填上各种值
        rs.ackedPackets = 5;
        rs.ackedBytes = 7000;
        rs.rttUs = 12345L;
        rs.appLimited = true;
        rs.ecnMarked = true;
        rs.delivered = 100;
        rs.priorDelivered = 50;
        rs.sendElapsedUs = 1000L;
        rs.ackElapsedUs = 800L;
        rs.isAckedRetrans = true;
        rs.minRttUs = 4500L;

        rs.reset();
        assertAllZero(rs);
    }

    private static void assertAllZero(RateSample rs) {
        assertThat(rs.ackedPackets).isZero();
        assertThat(rs.ackedBytes).isZero();
        assertThat(rs.rttUs).isZero();
        assertThat(rs.appLimited).isFalse();
        assertThat(rs.ecnMarked).isFalse();
        assertThat(rs.delivered).isZero();
        assertThat(rs.priorDelivered).isZero();
        assertThat(rs.sendElapsedUs).isZero();
        assertThat(rs.ackElapsedUs).isZero();
        assertThat(rs.isAckedRetrans).isFalse();
        assertThat(rs.minRttUs).isZero();
    }
}
