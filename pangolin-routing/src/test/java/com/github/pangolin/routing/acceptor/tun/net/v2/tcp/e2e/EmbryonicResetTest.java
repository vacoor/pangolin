package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
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
 * E2E:SYN_RECV 阶段的 embryonic_reset —— 对齐 Linux {@code checkReq}
 * 中 {@code RST / SYN → embryonic_reset} 以及 {@code ACK# 不等于 sndIsn+1}
 * 的销毁路径。
 *
 * <p>覆盖 R4.2c 迁到 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Listener#checkReq}
 * 的分支:
 * <ul>
 *   <li>客户端 SYN → 栈返 SYN-ACK(req 入半连接队列);</li>
 *   <li>客户端发 RST 给 req → {@code hasRst} 分支 → inet_csk_destroy_sock(req)
 *       立即销毁,不走 send_reset;</li>
 *   <li>或客户端发 ACK 但 ack_num ≠ sndIsn+1 → send_reset + destroy req;</li>
 * </ul>
 * 销毁后 syn queue 应清空,listener 可再接受新连接。
 */
class EmbryonicResetTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    private TcpStackHarness harness;
    private CapturingInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CapturingInitializer();
        harness = new TcpStackHarness(initializer);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("SYN_RECV 收到对端 RST → req 销毁,syn queue 空,不发 reset")
    void rstInSynRecvDestroysReqSilently() {
        // Step 1: 客户端 SYN
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        synAck.release();

        // syn queue 有 1 条
        assertThat(harness.handler().stack().listener().synQueueSize())
                .as("req in syn queue after SYN")
                .isEqualTo(1);

        // Step 2: 客户端 RST(模拟客户端放弃)
        harness.sendInbound(PacketFactory.rst(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1));
        harness.channel().runPendingTasks();

        // req 应已销毁,syn queue 空
        assertThat(harness.handler().stack().listener().synQueueSize())
                .as("req destroyed,syn queue clears")
                .isZero();

        // 不应有 RST 响应(RFC 9293 §3.5.2:don't RST a RST)
        Tcp4PacketBuf extra = harness.readOutboundTcp();
        if (extra != null) {
            try {
                assertThat(extra.isRst())
                        .as("no RST reply for peer RST during SYN_RECV")
                        .isFalse();
            } finally {
                extra.release();
            }
        }

        // 握手从未完成 → 无 handler
        assertThat(initializer.handler())
                .as("no CapturingHandler(3WHS aborted)")
                .isNull();
    }

    @Test
    @DisplayName("SYN_RECV 收到 ACK# 错误 → send_reset + req 销毁")
    void badAckNumTriggersResetAndDestroy() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        int serverIsn = synAck.tcpSeq();
        synAck.release();

        // 期望 ACK# = serverIsn+1;我们故意发 serverIsn+999
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 999));
        harness.channel().runPendingTasks();

        // req 销毁
        assertThat(harness.handler().stack().listener().synQueueSize())
                .as("bad ACK triggers embryonic reset → req destroyed")
                .isZero();

        // 栈应发 RST(send_reset)
        Tcp4PacketBuf rst = harness.readOutboundTcp();
        assertThat(rst).as("RST sent on bad ACK").isNotNull();
        try {
            assertThat(rst.isRst())
                    .as("response is RST")
                    .isTrue();
        } finally {
            rst.release();
        }

        assertThat(initializer.handler())
                .as("handshake never completed")
                .isNull();
    }

    @Test
    @DisplayName("RST 后再来一个 SYN(不同 ISN)→ 新的半连接进入 queue,可完成握手")
    void newSynAfterResetRecoverable() {
        // 第一条 SYN + RST
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        harness.readOutboundTcp().release();

        harness.sendInbound(PacketFactory.rst(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN + 1));
        harness.channel().runPendingTasks();

        // 同 4-tuple 换一个 ISN 重新来
        int newIsn = CLIENT_ISN + 5000;
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, newIsn));
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).as("new SYN should produce SYN-ACK").isNotNull();
        int isn2 = synAck.tcpSeq();
        try {
            assertThat(synAck.tcpAckNum()).isEqualTo(newIsn + 1);
        } finally {
            synAck.release();
        }

        // 完成新握手
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                newIsn + 1, isn2 + 1));
        harness.channel().runPendingTasks();

        assertThat(initializer.handler())
                .as("second handshake should complete")
                .isNotNull();
    }
}
