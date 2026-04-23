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
 * E2E:ESTABLISHED 收到对端 RST → {@code handler.onReset} 被回调 + sock 走向销毁。
 *
 * <p>覆盖 R5 rename / 整理 {@code tcp_reset} 路径时关键回归风险点:
 * <ul>
 *   <li>onReset 必须在 sock 销毁前被调用(pipeline.exceptionCaught 能拿到 cause);</li>
 *   <li>RST 段本身不会被栈回 ACK(RST 不触发响应);</li>
 *   <li>最终 sock 进入 TCP_CLOSED 或 onSocketDestroyed 被回调。</li>
 * </ul>
 */
class RstInboundTest {

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
    @DisplayName("ESTABLISHED 收 RST → onReset 被调,sock 销毁,无响应段")
    void peerRstTriggersOnResetAndDestroy() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.TCP_ESTABLISHED);
        assertThat(h.lastResetCause()).as("no reset yet").isNull();

        // 对端 RST,seq = CLIENT_ISN+1(对齐 rcv_nxt,tcp_reset_check 走 in-window 分支)
        // 栈在 resetIncoming 路径上会 fireExceptionCaught,EmbeddedChannel.writeInbound
        // 结束时会把它重新抛出 — 这是栈的**预期行为**,测试显式吞下。
        try {
            harness.sendInbound(PacketFactory.rst(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1));
        } catch (Exception expected) {
            assertThat(expected.getMessage()).contains("Connection reset");
        }

        harness.channel().runPendingTasks();

        // onReset 回调必须被触发,cause 非空
        assertThat(h.lastResetCause())
                .as("onReset should be invoked with an exception cause")
                .isNotNull();

        // sock 应销毁或处于 CLOSED 状态
        assertThat(h.destroyed() || h.sock().state() == TcpConnectionState.TCP_CLOSED)
                .as("sock should be destroyed or in TCP_CLOSED after RST")
                .isTrue();

        // RST 不应触发栈回包
        Tcp4PacketBuf extra = harness.readOutboundTcp();
        if (extra != null) {
            try {
                throw new AssertionError("unexpected outbound after RST: flags=0x"
                        + Integer.toHexString(extra.tcpFlags()));
            } finally {
                extra.release();
            }
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
}
