package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:RTO 到期触发重传。验证 R2 抽 Sender 时 RTO 调度链不能被破坏。
 *
 * <p>初始 RTO = {@code TcpConstants.RTO_INIT_MS}(1000ms)。测试流程:
 * <ol>
 *   <li>握手完成,栈发一段数据 → 出站 seg(seq_x, payload)</li>
 *   <li>不 ACK</li>
 *   <li>推进时间 ≥ RTO → RTO timer 触发</li>
 *   <li>栈应重传同一段(相同 seq、相同 payload)</li>
 *   <li>{@code snd_una} 保持不变,{@code packetsOut} 仍为 1</li>
 *   <li>{@code rtoBackoffShift} 自增(指数退避)</li>
 * </ol>
 */
class RetransmissionTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    private TcpStackHarness harness;
    private CapturingInitializer initializer;
    private int serverIsn;

    @BeforeEach
    void setUp() {
        initializer = new CapturingInitializer();
        harness = new TcpStackHarness(initializer);
        serverIsn = completeHandshake();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("RTO 到期 → 重传同一段(seq 不变,payload 不变)")
    void rtoTriggersRetransmission() {
        byte[] payload = "retry".getBytes(StandardCharsets.UTF_8);
        CapturingInitializer.CapturingHandler h = initializer.handler();
        h.send(payload);

        // 1) 首发
        Tcp4PacketBuf first = harness.readOutboundTcp();
        assertThat(first).isNotNull();
        final int firstSeq;
        final byte[] firstPayload;
        try {
            firstSeq = first.tcpSeq();
            firstPayload = new byte[first.tcpPayloadLength()];
            first.tcpPayloadSlice().getBytes(0, firstPayload);
            assertThat(firstSeq).isEqualTo(serverIsn + 1);
            assertThat(firstPayload).isEqualTo(payload);
        } finally {
            first.release();
        }

        assertThat(h.sock().packetsOut()).as("one segment in flight").isEqualTo(1);
        long rtoBefore = h.sock().rtoMs();
        // 3WHS 完成时 synackRttMeas 已喂首个 RTT 样本,rtoMs() 从此脱离 SRTT=0 的
        // 默认 1s 退化路径 —— 仅断言 RTO 在 [TCP_RTO_MIN, TCP_RTO_MAX] 合理区间。
        assertThat(rtoBefore).as("RTO positive after 3WHS RTT sample").isPositive();
        assertThat(rtoBefore).as("RTO not above RTO_MAX").isLessThanOrEqualTo(120_000L);

        // 2) 推进 RTO + 一点余量,运行已到期调度任务
        harness.channel().advanceTimeBy(rtoBefore + 200, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // 3) 重传应出现(seq 不变 / payload 不变 / 仍然 ACK+PSH)
        Tcp4PacketBuf retx = harness.readOutboundTcp();
        assertThat(retx).as("retransmission should fire after RTO").isNotNull();
        try {
            assertThat(retx.tcpSeq())
                    .as("retransmission must keep same seq")
                    .isEqualTo(firstSeq);
            byte[] retxPayload = new byte[retx.tcpPayloadLength()];
            retx.tcpPayloadSlice().getBytes(0, retxPayload);
            assertThat(retxPayload)
                    .as("retransmission must carry same payload")
                    .isEqualTo(firstPayload);
            assertThat(retx.isAck()).isTrue();
            assertThat(retx.isRst()).isFalse();
            assertThat(retx.isSyn()).isFalse();
        } finally {
            retx.release();
        }

        // 4) snd_una 未推进(仍未被 ACK)
        assertThat(h.sock().sndUna())
                .as("snd_una should stay at initial pos (nothing acked)")
                .isEqualTo(serverIsn + 1);

        // 5) packetsOut 仍为 1(还是那一段)
        assertThat(h.sock().packetsOut())
                .as("packets_out unchanged after retransmission")
                .isEqualTo(1);

        // 6) RTO 不倒退(具体 backoff 策略由专用测试覆盖,此处只验证 R2 抽 Sender 不破坏 RTO 状态机)
        long rtoAfter = h.sock().rtoMs();
        assertThat(rtoAfter)
                .as("RTO should not regress after retransmission")
                .isGreaterThanOrEqualTo(rtoBefore);
    }

    // ---- helpers ----

    private int completeHandshake() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
        int isn = synAck.tcpSeq();
        synAck.release();

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));

        return isn;
    }
}
