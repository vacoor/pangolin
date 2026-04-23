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

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:对端 FIN 触发半关闭语义。
 *
 * <p>覆盖 R4 拆 listener / 整理 FSM 时 close 相关迁移不能破:
 * <ul>
 *   <li>ESTABLISHED + 收 FIN → 栈回 ACK 且迁移到 {@code CLOSE_WAIT};</li>
 *   <li>{@code handler.onPeerFin()} 被调用(上层感知 peer half-close)。</li>
 * </ul>
 */
class FinShutdownTest {

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
    @DisplayName("ESTABLISHED 收 FIN → ACK、CLOSE_WAIT、onPeerFin 回调")
    void peerFinAckAndCloseWait() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.TCP_ESTABLISHED);
        assertThat(h.peerFinCount()).as("no FIN yet").isZero();

        // 客户端发 FIN(seq = CLIENT_ISN+1,ack = serverIsn+1)
        harness.sendInbound(PacketFactory.fin(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));

        // 推进时钟以防 ACK 被延迟
        harness.channel().advanceTimeBy(200, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // 状态:ESTABLISHED → CLOSE_WAIT(本端仍可写)
        assertThat(h.sock().state())
                .as("state should be CLOSE_WAIT after peer FIN")
                .isEqualTo(TcpConnectionState.CLOSE_WAIT);

        // onPeerFin 应被调用一次
        assertThat(h.peerFinCount())
                .as("onPeerFin should be fired exactly once")
                .isEqualTo(1);

        // 栈应回 ACK,ack_num = CLIENT_ISN + 2(FIN 占 1 个序号)
        Tcp4PacketBuf reply = drainAckAtLeast(CLIENT_ISN + 2);
        assertThat(reply).as("expected ACK after FIN").isNotNull();
        try {
            assertThat(reply.isAck()).isTrue();
            assertThat(reply.isRst()).isFalse();
            assertThat(reply.tcpAckNum())
                    .as("ACK number should advance past FIN (ISN+1+1)")
                    .isEqualTo(CLIENT_ISN + 2);
        } finally {
            reply.release();
        }
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

    private Tcp4PacketBuf drainAckAtLeast(int expectedAck) {
        for (int i = 0; i < 8; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.isAck() && !p.isSyn() && p.tcpAckNum() == expectedAck) {
                return p;
            }
            p.release();
        }
        return null;
    }
}
