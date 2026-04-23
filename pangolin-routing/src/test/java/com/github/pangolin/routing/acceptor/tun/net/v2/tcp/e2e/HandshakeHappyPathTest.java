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
 * E2E:被动 3WHS happy path。
 *
 * <p>时序:
 * <pre>
 *   client              server(stack)
 *   --- SYN seq=1000 ----->          (LISTEN → SYN_RECV 半连接入队)
 *                         &lt;----- SYN-ACK seq=S, ack=1001
 *   --- ACK seq=1001, ack=S+1 -----> (SYN_RECV → ESTABLISHED, onEstablished 回调)
 * </pre>
 */
class HandshakeHappyPathTest {

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
    @DisplayName("SYN → SYN-ACK → ACK 三次握手完成,onEstablished 被调用")
    void happyPath() {
        // 1) 客户端发 SYN
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        // 2) 栈应发出 SYN-ACK
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).as("expected SYN-ACK after client SYN").isNotNull();
        final int serverIsn;
        try {
            assertThat(synAck.isSyn()).as("reply should be SYN-ACK (SYN bit)").isTrue();
            assertThat(synAck.isAck()).as("reply should be SYN-ACK (ACK bit)").isTrue();
            assertThat(synAck.isRst()).as("reply must not be RST").isFalse();
            assertThat(synAck.tcpSrcPort()).isEqualTo(SERVER_PORT);
            assertThat(synAck.tcpDstPort()).isEqualTo(CLIENT_PORT);
            // ack number = client ISN + 1(SYN 消耗一个序号)
            assertThat(synAck.tcpAckNum()).isEqualTo(CLIENT_ISN + 1);
            serverIsn = synAck.tcpSeq();
        } finally {
            synAck.release();
        }

        // 握手未完成前不应触发 onEstablished
        assertThat(initializer.handler()).as("no onEstablished before final ACK").isNull();

        // 3) 客户端回 ACK 完成握手
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));

        // 4) onEstablished 已被调用,sock 进入 ESTABLISHED
        CapturingInitializer.CapturingHandler handler = initializer.handler();
        assertThat(handler).as("onEstablished should have been invoked").isNotNull();
        assertThat(handler.sock().state())
                .as("sock should be ESTABLISHED after final ACK")
                .isEqualTo(TcpConnectionState.TCP_ESTABLISHED);

        // 5) 握手过程中服务端不应主动发送数据(没有握手外的出包)
        Tcp4PacketBuf extra = harness.readOutboundTcp();
        if (extra != null) {
            try {
                // 允许可能的延迟 ACK 或其他控制包,但不应有 payload
                assertThat(extra.tcpPayloadLength())
                        .as("no payload should be sent by server after 3WHS").isZero();
            } finally {
                extra.release();
            }
        }
    }
}
