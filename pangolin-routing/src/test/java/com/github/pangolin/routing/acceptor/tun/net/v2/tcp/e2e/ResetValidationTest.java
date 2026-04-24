package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:RFC 5961 §3.2 — ESTABLISHED 连接收到 RST 的三段式校验。
 *
 * <p>分支覆盖({@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingPreValidator}
 * 的 RST 处理):
 * <ul>
 *   <li>seq == rcv_nxt → 严格匹配,接受 reset(已由 {@code RstInboundTest} 覆盖);</li>
 *   <li>seq 在接收窗口内但 ≠ rcv_nxt → <b>challenge ACK</b>,RST 不生效(本测试);</li>
 *   <li>seq 完全在窗口外 → <b>静默丢弃</b>,无 challenge ACK(本测试)。</li>
 * </ul>
 */
class ResetValidationTest {

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
    @DisplayName("窗内 RST(seq ≠ rcv_nxt)→ challenge ACK,sock 不 reset")
    void inWindowRstButNotRcvNxtTriggersChallengeAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.TCP_ESTABLISHED);

        // 构造窗内但非 rcv_nxt 的 RST:seq = rcv_nxt + 100(仍在 65535 窗口内)
        harness.sendInbound(PacketFactory.rst(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1 + 100));
        harness.channel().runPendingTasks();

        // sock 不应 reset
        assertThat(h.lastResetCause())
                .as("inner-window RST should NOT reset connection")
                .isNull();
        assertThat(h.sock().state())
                .as("state stays ESTABLISHED")
                .isEqualTo(TcpConnectionState.TCP_ESTABLISHED);
        assertThat(h.destroyed()).isFalse();

        // 应出 challenge ACK(ack_num = rcv_nxt)
        Tcp4PacketBuf challenge = harness.readOutboundTcp();
        assertThat(challenge).as("challenge ACK for inner-window RST").isNotNull();
        try {
            assertThat(challenge.isAck()).isTrue();
            assertThat(challenge.isRst()).isFalse();
            assertThat(challenge.tcpAckNum())
                    .as("challenge ACK points to current rcv_nxt")
                    .isEqualTo(CLIENT_ISN + 1);
        } finally {
            challenge.release();
        }
    }

    @Test
    @DisplayName("窗外 RST(seq 远超 rcv_nxt + rwnd)→ 静默丢弃,无响应")
    void outOfWindowRstSilentlyDropped() {
        CapturingInitializer.CapturingHandler h = initializer.handler();

        // seq 远超窗口上限 → sequenceCheck 返回 OVERWINDOW
        // validator 在非 RST 分支走 outOfWindow + sendAck,但在 RST 分支 → 如果 resetCheck
        // 也不通过(seq != rcv_nxt - 1),则静默返回。
        harness.sendInbound(PacketFactory.rst(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1 + 10_000_000));
        harness.channel().runPendingTasks();

        // sock 不应 reset
        assertThat(h.lastResetCause()).isNull();
        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.TCP_ESTABLISHED);

        // 无响应(抗 RFC 5961 §3.2 盲窗攻击)
        assertThat(harness.outboundSize())
                .as("out-of-window RST must be silently dropped")
                .isZero();
    }

    // ---- helpers ----

    private int completeHandshake() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        int isn = synAck.tcpSeq();
        synAck.release();

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();
        return isn;
    }
}
