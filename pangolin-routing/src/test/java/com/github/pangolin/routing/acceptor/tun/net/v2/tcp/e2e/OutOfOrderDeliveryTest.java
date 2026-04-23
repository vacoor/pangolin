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

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:乱序段到达 → OFO 队列暂存 → 前段到齐后合并交付。覆盖 R3 抽 Receiver 的核心路径
 * ({@code tcp_data_queue_ofo} 入队 + {@code tcp_ofo_queue} 排干合并)。
 */
class OutOfOrderDeliveryTest {

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
    @DisplayName("后段先到(seq+5)→ 暂存 OFO,前段到达后按序交付全部")
    void outOfOrderThenFillGap() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        byte[] part1 = "AAAAA".getBytes();                        // seq = CLIENT_ISN+1..+5
        byte[] part2 = "BBBBB".getBytes();                        // seq = CLIENT_ISN+6..+10
        int seqPart1 = CLIENT_ISN + 1;
        int seqPart2 = CLIENT_ISN + 1 + part1.length;

        // 先发后段(seqPart2),制造 gap
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                seqPart2, serverIsn + 1,
                Unpooled.wrappedBuffer(part2)));

        // OFO 段**不应**立即交付 handler
        assertThat(h.inboundPayloads())
                .as("OFO segment must not be delivered before gap is filled")
                .isEmpty();

        // 补上前段
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                seqPart1, serverIsn + 1,
                Unpooled.wrappedBuffer(part1)));

        // 两段应按序交付 handler(可能合成一个 payload,也可能分两段)
        byte[] combined = concat(h.inboundPayloads());
        byte[] expected = concat(java.util.Arrays.asList(part1, part2));
        assertThat(combined)
                .as("handler should receive part1+part2 in order")
                .isEqualTo(expected);

        // 推进时钟触发 ACK
        harness.channel().advanceTimeBy(200, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // 最终 ACK 号应到达 CLIENT_ISN + 1 + part1 + part2
        int expectedAck = CLIENT_ISN + 1 + part1.length + part2.length;
        Tcp4PacketBuf finalAck = drainAckAtLeast(expectedAck);
        assertThat(finalAck).as("final ACK should cover both segments").isNotNull();
        try {
            assertThat(finalAck.tcpAckNum()).isEqualTo(expectedAck);
        } finally {
            finalAck.release();
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
        for (int i = 0; i < 16; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.isAck() && !p.isSyn() && p.tcpAckNum() >= expectedAck) {
                return p;
            }
            p.release();
        }
        return null;
    }

    private static byte[] concat(java.util.List<byte[]> parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
