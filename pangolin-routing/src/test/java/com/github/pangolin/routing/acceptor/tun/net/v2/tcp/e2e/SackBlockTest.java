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

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:SACK(RFC 2018)接收侧 —— 对齐 Linux {@code tcp_sacktag_write_queue}。
 * 客户端通过 SACK 块告知"左 ACK 覆盖之外,哪些段我已经收到",
 * Sender 根据 SACK 块把对应段标记 {@code TCPCB_SACKED_ACKED} 并累加
 * {@code sacked_out} 计数(R2.3 下沉到 {@link Sender})。
 *
 * <p>场景:
 * <ol>
 *   <li>3WHS 完成;</li>
 *   <li>栈发 3 个单字节段(seq A, A+1, A+2);</li>
 *   <li>客户端 ACK(ack_num = A)+ SACK 块 [A+1, A+3]—— 告知"A 未收,
 *       但 A+1、A+2 已到";</li>
 *   <li>验证:packets_out = 3,sacked_out = 2。</li>
 * </ol>
 *
 * <p>覆盖 {@code TcpAck.sacktagWriteQueue} 从 SACK option 到 Sender.sackedOut
 * 递增的完整路径。
 */
class SackBlockTest {

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
    @DisplayName("SACK 块 [seq2, seq4) 标记中段已收 → sacked_out = 2,packets_out 不变")
    void sackBlockMarksMiddleSegmentsAcked() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // 发 3 个 1-byte 段
        int[] seqs = new int[3];
        int[] lens = new int[3];
        for (int i = 0; i < 3; i++) {
            h.send(new byte[] { (byte) ('a' + i) });
            Tcp4PacketBuf out = harness.readOutboundTcp();
            assertThat(out).isNotNull();
            seqs[i] = out.tcpSeq();
            lens[i] = out.tcpPayloadLength();
            out.release();
        }

        assertThat(sender.packetsOut()).as("3 segments in flight").isEqualTo(3);
        assertThat(sender.sackedOut()).as("no SACK yet").isZero();

        // 客户端 ACK:ack_num = seq0(段 0 未收);
        // SACK 块 1 条:[seq1, seq2+1) 标记段 1 和 段 2 已被 SACK
        int leftEdge = seqs[1];
        int rightEdge = seqs[2] + lens[2];
        byte[] sackOpt = sackOption(leftEdge, rightEdge);

        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(seqs[0])  // 累计 ACK 尚未覆盖任何已发段
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(sackOpt)
                .build());
        harness.channel().runPendingTasks();

        // packets_out 不变(仍是 3,SACK 不从 RTX 队列移除 — 累计 ACK 才移除)
        assertThat(sender.packetsOut())
                .as("SACK doesn't decrement packets_out; only cumulative ACK does")
                .isEqualTo(3);

        // sacked_out 应为 2(段 1 + 段 2 被 SACK tagged)
        assertThat(sender.sackedOut())
                .as("two segments covered by SACK block → sacked_out = 2")
                .isEqualTo(2);

        // packetsInFlight = packets_out - sacked_out = 3 - 2 = 1
        // (lost_out = 0 在这个场景)
        assertThat(h.sock().packetsInFlight())
                .as("effective in-flight = packets_out - sacked_out")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("累计 ACK 追上 SACK 块 → sacked_out 归零(cleanRtxQueue)")
    void cumulativeAckReleasesSackedSegments() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        int[] seqs = new int[3];
        int[] lens = new int[3];
        for (int i = 0; i < 3; i++) {
            h.send(new byte[] { (byte) ('a' + i) });
            Tcp4PacketBuf out = harness.readOutboundTcp();
            seqs[i] = out.tcpSeq();
            lens[i] = out.tcpPayloadLength();
            out.release();
        }

        // SACK 段 1 和 段 2
        byte[] sackOpt = sackOption(seqs[1], seqs[2] + lens[2]);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1).ack(seqs[0])
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(sackOpt)
                .build());
        harness.channel().runPendingTasks();
        assertThat(sender.sackedOut()).isEqualTo(2);

        // 再发累计 ACK 追上所有段
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, seqs[2] + lens[2]));
        harness.channel().runPendingTasks();

        assertThat(sender.packetsOut())
                .as("all segments released from RTX queue")
                .isZero();
        assertThat(sender.sackedOut())
                .as("sacked_out decrements with cleanRtxQueue")
                .isZero();
    }

    // ---- helpers ----

    /** 构造 1-block SACK option(NOP NOP Kind=5 Len=10 left right),共 12 字节,4 字节对齐。 */
    private static byte[] sackOption(int leftEdge, int rightEdge) {
        byte[] o = new byte[12];
        o[0] = 0x01;  // NOP
        o[1] = 0x01;  // NOP
        o[2] = 0x05;  // Kind=5 (SACK)
        o[3] = 0x0A;  // Length = 2 + 8*1 = 10
        o[4] = (byte) (leftEdge >>> 24);
        o[5] = (byte) (leftEdge >>> 16);
        o[6] = (byte) (leftEdge >>> 8);
        o[7] = (byte) leftEdge;
        o[8] = (byte) (rightEdge >>> 24);
        o[9] = (byte) (rightEdge >>> 16);
        o[10] = (byte) (rightEdge >>> 8);
        o[11] = (byte) rightEdge;
        return o;
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
