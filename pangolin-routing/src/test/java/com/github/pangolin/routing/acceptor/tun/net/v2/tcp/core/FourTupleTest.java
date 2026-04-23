package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FourTuple} 值语义:equals / hashCode 覆盖全部 4 字段,
 * 构造时对地址字节数组做防御性 clone。
 */
class FourTupleTest {

    private static final byte[] ADDR_A = {10, 0, 0, 1};
    private static final byte[] ADDR_B = {10, 0, 0, 2};

    @Test
    void equalsIsReflexiveAndSymmetric() {
        FourTuple t1 = FourTuple.of(ADDR_A, 1111, ADDR_B, 80);
        FourTuple t2 = FourTuple.of(ADDR_A, 1111, ADDR_B, 80);

        assertThat(t1).isEqualTo(t1);
        assertThat(t1).isEqualTo(t2).hasSameHashCodeAs(t2);
        assertThat(t2).isEqualTo(t1);
    }

    @Test
    void differentFieldsBreakEquality() {
        FourTuple base = FourTuple.of(ADDR_A, 1111, ADDR_B, 80);

        assertThat(base).isNotEqualTo(FourTuple.of(ADDR_B, 1111, ADDR_B, 80)); // src addr
        assertThat(base).isNotEqualTo(FourTuple.of(ADDR_A, 2222, ADDR_B, 80)); // src port
        assertThat(base).isNotEqualTo(FourTuple.of(ADDR_A, 1111, ADDR_A, 80)); // dst addr
        assertThat(base).isNotEqualTo(FourTuple.of(ADDR_A, 1111, ADDR_B, 81)); // dst port
    }

    @Test
    void constructorClonesAddressBytes() {
        byte[] mutable = ADDR_A.clone();
        FourTuple t = FourTuple.of(mutable, 1111, ADDR_B, 80);
        mutable[0] = 99; // 污染输入
        // 构造后的 FourTuple 不受外部修改影响
        assertThat(t.srcAddrBytes()[0]).isEqualTo((byte) 10);
    }

    @Test
    void notEqualToOtherType() {
        FourTuple t = FourTuple.of(ADDR_A, 1111, ADDR_B, 80);
        assertThat(t).isNotEqualTo("not a tuple");
        assertThat(t).isNotEqualTo(null);
    }

    @Test
    void toStringContainsEndpoints() {
        FourTuple t = FourTuple.of(ADDR_A, 1111, ADDR_B, 80);
        assertThat(t.toString())
                .contains("10.0.0.1:1111")
                .contains("10.0.0.2:80");
    }
}
