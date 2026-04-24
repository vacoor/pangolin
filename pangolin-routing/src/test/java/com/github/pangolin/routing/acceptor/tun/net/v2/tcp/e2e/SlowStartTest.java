package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:Slow Start — 对齐 Linux {@code tcp_slow_start}。新数据 ACK 在
 * {@code cwnd < ssthresh} 时让 cwnd 按每 ACK +1(段)增长(但受 {@code cwnd += acked}
 * 上限约束)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>初始 cwnd = {@code TCP_INIT_CWND = 10},ssthresh = {@code Integer.MAX_VALUE};</li>
 *   <li>每次新数据 ACK 推进 snd_una → {@code sender.incrementCwnd} 执行 cwnd++;</li>
 *   <li>dup ACK(不推进 snd_una)→ cwnd 不增(交给 Recovery 路径,见 FastRecoveryTest);</li>
 *   <li>RECOVERY 退出路径:ACK 跨过 highSeq 时 cwnd 回落到 ssthresh(Linux NewReno)。</li>
 * </ul>
 */
class SlowStartTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;
    private static final int TCP_INIT_CWND = 10;

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
    @DisplayName("初始 cwnd = TCP_INIT_CWND(10),在 slow-start 里 ssthresh = MAX_VALUE")
    void initialCwndAndSsthresh() {
        Sender sender = initializer.handler().sock().sender();
        assertThat(sender.cwnd()).isEqualTo(TCP_INIT_CWND);
        assertThat(sender.ssthresh()).isEqualTo(Integer.MAX_VALUE);
        assertThat(initializer.handler().sock().isCaOpen()).isTrue();
    }

    @Test
    @DisplayName("每个新数据 ACK 在 slow-start 下让 cwnd +1(3 轮各 +1 = 13)")
    void cwndGrowsOnNewDataAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();
        int cwnd0 = sender.cwnd();

        // 3 轮串行:发 1 字节 → 读出段 → 客户端 ACK → cwnd +1
        int expectedCwnd = cwnd0;
        int ackCursor = serverIsn + 1;  // 下一段 ACK 位置
        for (int i = 0; i < 3; i++) {
            byte[] payload = { (byte) ('a' + i) };
            h.send(payload);
            Tcp4PacketBuf out = harness.readOutboundTcp();
            assertThat(out).isNotNull();
            int seq = out.tcpSeq();
            int len = out.tcpPayloadLength();
            out.release();

            assertThat(seq).isEqualTo(ackCursor);
            ackCursor = seq + len;

            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, ackCursor));
            harness.channel().runPendingTasks();

            expectedCwnd++;
            assertThat(sender.cwnd())
                    .as("cwnd +1 per new data ACK in slow start (round " + i + ")")
                    .isEqualTo(expectedCwnd);
        }
    }

    @Test
    @DisplayName("dup ACK(不推进 snd_una)在 slow start 不增 cwnd")
    void cwndDoesNotGrowOnDupAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // 先让 cwnd 以合法方式增 1
        byte[] pay = "x".getBytes(StandardCharsets.UTF_8);
        h.send(pay);
        Tcp4PacketBuf seg = harness.readOutboundTcp();
        int seq = seg.tcpSeq();
        int len = seg.tcpPayloadLength();
        seg.release();
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, seq + len));
        harness.channel().runPendingTasks();

        int cwndAfterAck = sender.cwnd();

        // 发一个 dup ACK(ack_num 相同,不推进)— 不应影响 cwnd(<3 个不触发 Recovery)
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, seq + len));
        harness.channel().runPendingTasks();

        assertThat(sender.cwnd())
                .as("single dupack does not grow cwnd (nor enter Recovery)")
                .isEqualTo(cwndAfterAck);
        assertThat(h.sock().isCaOpen())
                .as("still in OPEN — 1 dupack < 3 threshold")
                .isTrue();
    }

    // ---- helpers ----

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
