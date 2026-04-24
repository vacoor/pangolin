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

    @Test
    @DisplayName("多 SACK 块:ACK 中带 2 个不连续 SACK 块 → 两段分别被 tagged(sacked_out=2)")
    void multipleSackBlocksCoverNonContiguousSegments() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // 发 5 个 1-byte 段 S, S+1, S+2, S+3, S+4
        int[] seqs = new int[5];
        for (int i = 0; i < 5; i++) {
            h.send(new byte[] { (byte) ('a' + i) });
            Tcp4PacketBuf out = harness.readOutboundTcp();
            seqs[i] = out.tcpSeq();
            out.release();
        }

        // 两个 SACK 块:[seqs[1], seqs[2]) 和 [seqs[3], seqs[4]+1)
        // 等价于"seg 0、2、4 未收,seg 1、3 已收" 的乱序场景
        byte[] sackOpt = sackOption2(seqs[1], seqs[1] + 1, seqs[3], seqs[3] + 1);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1).ack(seqs[0])
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(sackOpt)
                .build());
        harness.channel().runPendingTasks();

        assertThat(sender.sackedOut())
                .as("2 SACK blocks covering 2 segments → sacked_out = 2")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("DSACK 块(RFC 2883 Case 1)→ rackDsackSeen 触发 reoWndSteps++")
    void dsackTriggersReoWndStepIncrement() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();
        int initialReoSteps = sender.rackReoWndSteps();
        assertThat(initialReoSteps).as("RACK reoWndSteps default 1").isEqualTo(1);

        // 阶段 1:发 seg0 seg1,让客户端累计 ACK seg0(推进 sender.delivered=1,
        // seg1 保留 RTX 以便后续 DSACK 处理分支生效)
        h.send(new byte[] { 'a' });
        Tcp4PacketBuf s0 = harness.readOutboundTcp();
        int seg0Seq = s0.tcpSeq();
        s0.release();

        h.send(new byte[] { 'b' });
        Tcp4PacketBuf s1 = harness.readOutboundTcp();
        int seg1Seq = s1.tcpSeq();
        s1.release();

        // 累计 ACK seg0 → seg0 从 RTX 清出,sender.delivered 推进
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, seg0Seq + 1));
        harness.channel().runPendingTasks();
        assertThat(sender.delivered())
                .as("cumulative ACK for seg0 increments delivered")
                .isGreaterThanOrEqualTo(1);

        // 阶段 2:发 seg2(其 tx.delivered 快照到已推进的 delivered)
        h.send(new byte[] { 'c' });
        Tcp4PacketBuf s2 = harness.readOutboundTcp();
        int seg2Seq = s2.tcpSeq();
        s2.release();

        // 阶段 3:构造 DSACK 包 —— 首块为 DSACK([seg0.seq, seg0.end) 已在 cum_ack 之下),
        // 次块为 SACK 标记 seg2。DSACK 守卫要求首块 end ≤ priorSndUna(= seg0+1 这里)。
        byte[] dsackOpt = sackOption2(
                seg0Seq, seg0Seq + 1,          // DSACK:"我已收过 seg0"
                seg2Seq, seg2Seq + 1);         // SACK:seg2 已收(seg1 仍是空洞)
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(seg0Seq + 1)  // cum 不前进,但 DSACK 通道激活
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(dsackOpt)
                .build());
        harness.channel().runPendingTasks();

        // DSACK 被识别,reo_wnd_steps 递增
        assertThat(sender.rackReoWndSteps())
                .as("DSACK 守卫命中 → reoWndSteps 从 1 步进到 2")
                .isGreaterThan(initialReoSteps);
        // rackDsackSeen 已消费清零
        assertThat(sender.rackDsackSeen())
                .as("rackDsackSeen 被 tcpRackUpdateReoWnd 消费")
                .isFalse();
        // seg2 已被 SACK 标记
        assertThat(sender.sackedOut())
                .as("次块 SACK tagged seg2")
                .isEqualTo(1);
        // seg1 还在 RTX(未 SACK 也未 cum-ACK)
        assertThat(sender.packetsOut())
                .as("seg1 + seg2 仍在 RTX(seg2 SACKed 不移除)")
                .isEqualTo(2);
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

    /** 构造 2-block SACK option(NOP NOP Kind=5 Len=18 + 2 块),共 20 字节。 */
    private static byte[] sackOption2(int l1, int r1, int l2, int r2) {
        byte[] o = new byte[20];
        o[0] = 0x01;
        o[1] = 0x01;
        o[2] = 0x05;
        o[3] = 0x12;  // Length = 2 + 8*2 = 18
        putInt(o, 4, l1);
        putInt(o, 8, r1);
        putInt(o, 12, l2);
        putInt(o, 16, r2);
        return o;
    }

    private static void putInt(byte[] o, int off, int v) {
        o[off]     = (byte) (v >>> 24);
        o[off + 1] = (byte) (v >>> 16);
        o[off + 2] = (byte) (v >>> 8);
        o[off + 3] = (byte) v;
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
