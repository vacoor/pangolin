package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
 * E2E:FIN piggyback 在数据段上 —— 对齐 Linux {@code tcp_data_queue} 的 FIN 识别路径。
 *
 * <p>常见真实场景:客户端发完数据后立即想关闭,把 FIN 位和最后一段 payload 合并在同一段。
 * 栈需要:
 * <ol>
 *   <li>正常投递 payload 到 handler;</li>
 *   <li>识别 FIN,推进 rcv_nxt 多 1(FIN 占一个 seq);</li>
 *   <li>回 ACK(ack_num = seq + payload + 1);</li>
 *   <li>迁 ESTABLISHED → CLOSE_WAIT;</li>
 *   <li>通知 handler.onPeerFin。</li>
 * </ol>
 *
 * <p>覆盖 Receiver.handleDataQueue + queueAndOut 内 FIN 合并判定(R4.2b-4f 迁移)。
 */
class DataWithFinTest {

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
    @DisplayName("数据段带 FIN → 交付 payload + 迁 CLOSE_WAIT + 回 ACK(ack=seq+len+1)")
    void dataWithFinFlagDeliversAndTransitionsToCloseWait() {
        CapturingInitializer.CapturingHandler h = initializer.handler();

        byte[] payload = "bye".getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.wrappedBuffer(payload);

        // data + FIN 同段(ACK|PSH|FIN)
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(serverIsn + 1)
                .flags(PacketFactory.TCP_FLAG_ACK | PacketFactory.TCP_FLAG_PSH | PacketFactory.TCP_FLAG_FIN)
                .payload(buf)
                .build());
        harness.channel().advanceTimeBy(250, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // payload 交付
        assertThat(h.inboundPayloads())
                .as("payload delivered despite FIN piggyback")
                .hasSize(1);
        assertThat(h.inboundPayloads().get(0))
                .as("payload bytes match")
                .isEqualTo(payload);

        // peer FIN 回调触发
        assertThat(h.peerFinCount())
                .as("onPeerFin fired exactly once")
                .isEqualTo(1);

        // 状态迁移 CLOSE_WAIT
        assertThat(h.sock().state())
                .as("FSM moves ESTABLISHED → CLOSE_WAIT")
                .isEqualTo(TcpConnectionState.CLOSE_WAIT);

        // rcv_nxt 推进 payload.length + 1(FIN 占 1 seq)
        assertThat(h.sock().rcvNxt())
                .as("rcv_nxt advances by payload + 1 (FIN consumes one seq)")
                .isEqualTo(CLIENT_ISN + 1 + payload.length + 1);

        // 应有一个 ACK 回包 ack_num = seq + payload + 1
        Tcp4PacketBuf ack = drainForAckNum(CLIENT_ISN + 1 + payload.length + 1);
        assertThat(ack).as("ACK for data+FIN must be emitted").isNotNull();
        try {
            assertThat(ack.isAck()).isTrue();
            assertThat(ack.isFin()).isFalse();
        } finally {
            ack.release();
        }
    }

    // ---- helpers ----

    private Tcp4PacketBuf drainForAckNum(int expected) {
        for (int i = 0; i < 8; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.isAck() && p.tcpAckNum() == expected) return p;
            p.release();
        }
        return null;
    }

    private int completeHandshake() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        int isn = synAck.tcpSeq();
        synAck.release();
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();
        return isn;
    }
}
