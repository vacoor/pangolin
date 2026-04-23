package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
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
 * E2E:握手完成后发送数据段,验证接收路径交付到 {@code TcpSockHandler.onInboundData}
 * 并 ACK 推进。
 */
class DataTransferTest {

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
    @DisplayName("单段数据 → handler 收到 payload,栈 ACK 推进(含延迟 ACK 推进)")
    void singleSegmentDelivery() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.wrappedBuffer(payload);

        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1, buf));

        // handler 应收到 payload
        CapturingInitializer.CapturingHandler h = initializer.handler();
        assertThat(h.inboundPayloads())
                .as("handler should receive exactly one payload")
                .hasSize(1);
        assertThat(h.inboundPayloads().get(0))
                .as("payload bytes should match").isEqualTo(payload);

        // 栈 ACK 可能是 immediate(quickack)或 delayed(40ms 定时器)。
        // 推进 EmbeddedChannel 时钟 200ms,覆盖延迟 ACK 最大窗口,然后跑已到期调度任务。
        harness.channel().advanceTimeBy(200, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // 栈应回一个 ACK 段(ack 号 = client_seq + payload.len)
        Tcp4PacketBuf reply = drainAckAtLeast(CLIENT_ISN + 1 + payload.length);
        assertThat(reply).as("expected ACK after data").isNotNull();
        try {
            assertThat(reply.isAck()).isTrue();
            assertThat(reply.isRst()).isFalse();
            assertThat(reply.tcpPayloadLength())
                    .as("ACK should carry no payload").isZero();
            assertThat(reply.tcpAckNum())
                    .as("ACK should advance past received data")
                    .isEqualTo(CLIENT_ISN + 1 + payload.length);
        } finally {
            reply.release();
        }
    }

    // ---- helpers ----

    /** 完成 3WHS,返回 server ISN(从 SYN-ACK seq 读回)。 */
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

    /**
     * 连续读出站包直到遇到一个 ack >= expectedMinAck 的 ACK(跳过可能的重复 ACK 或残包)。
     * 返回该 ACK(调用方负责 release);超限未命中则返回 null 并 release 扫描过的包。
     */
    private Tcp4PacketBuf drainAckAtLeast(int expectedMinAck) {
        for (int i = 0; i < 8; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.isAck() && !p.isSyn() && p.tcpAckNum() == expectedMinAck) {
                return p;
            }
            p.release();
        }
        return null;
    }
}
