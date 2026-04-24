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
 * E2E:RACK(RFC 8985)按时间窗口探测丢失 —— 对齐 Linux
 * {@code tcp_rack_detect_loss}(net/ipv4/tcp_recovery.c)。
 *
 * <p>场景:
 * <ol>
 *   <li>发 seg0(t=0);</li>
 *   <li>sleep ~50ms 制造时间缝;</li>
 *   <li>发 seg1、seg2(t≈50ms,sentTimeUs 大约与 seg0 相差 50000μs);</li>
 *   <li>客户端发一个 dup ACK(cum=seg0.seq 不推进)+ SACK [seg1.seq, seg2.end) ——
 *       seg1、seg2 被 tagged SACKED,触发 {@code sock.updateRack(seg2.sentUs, rtt)}:
 *       {@code rackMstamp} 推到 seg2 的发送时戳;</li>
 *   <li>{@code sacktagWriteQueue} 尾部调 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck#rackDetectLoss}
 *       (newlyTagged &gt; 0):遍历 RTX 队列,未 SACK 未 LOST 且 sentUs &lt; rackMstamp - reoWnd
 *       的 seg0 被标 {@code TCPCB_LOST},{@code lostOut} 自增。</li>
 * </ol>
 *
 * <p>reoWnd 被夹到 {@code [1ms, srtt>>3]};50ms 的时间缝远超 1ms,必然超出 reoWnd,
 * 因此无论 srtt 具体值如何都能命中。
 *
 * <p>本测试只覆盖单轮 SACK 触发的 RACK 判定,不进入 Recovery —— 一个 dupack 不足以
 * 触发 3-dupack Fast Retransmit;entry 由外层 reordering/TLP/后续 ACK 推动。
 */
class RackLostDetectionTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;
    private static final int MSS = 1460;

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
    @DisplayName("SACK 跳过最早段 + 时间缝 > reoWnd → rackDetectLoss 把 seg0 标 LOST")
    void rackMarksEarlierGapSegmentAsLost() throws InterruptedException {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // Step 1:发 seg0(t0)
        byte[] p0 = new byte[MSS];
        for (int i = 0; i < MSS; i++) p0[i] = 'a';
        h.send(p0);
        Tcp4PacketBuf s0 = harness.readOutboundTcp();
        int seg0Seq = s0.tcpSeq();
        s0.release();

        // Step 2:留出 50ms 时间缝(真实时间推进,System.nanoTime 前进 —— TcpSegment.sentTimeUs
        // 以此为源,与 advanceTimeBy 的调度时钟无关)
        Thread.sleep(50);

        // Step 3:发 seg1、seg2(t1 ≈ t0 + 50ms)
        byte[] p1 = new byte[MSS];
        for (int i = 0; i < MSS; i++) p1[i] = 'b';
        h.send(p1);
        Tcp4PacketBuf s1 = harness.readOutboundTcp();
        int seg1Seq = s1.tcpSeq();
        int seg1Len = s1.tcpPayloadLength();
        s1.release();

        byte[] p2 = new byte[MSS];
        for (int i = 0; i < MSS; i++) p2[i] = 'c';
        h.send(p2);
        Tcp4PacketBuf s2 = harness.readOutboundTcp();
        int seg2Seq = s2.tcpSeq();
        int seg2Len = s2.tcpPayloadLength();
        s2.release();

        // 3 段在飞;lostOut 仍为 0,sackedOut 也是 0
        assertThat(sender.packetsOut()).as("3 in-flight").isEqualTo(3);
        assertThat(sender.lostOut()).as("no LOST before SACK").isZero();
        assertThat(sender.sackedOut()).as("no SACK before ACK").isZero();
        assertThat(sender.rackMstamp())
                .as("rackMstamp not set until SACK arrives")
                .isZero();

        // Step 4:dup ACK(cum=seg0.seq 不推进)+ SACK [seg1.seq, seg2.end)
        byte[] sackOpt = sackOption1(seg1Seq, seg2Seq + seg2Len);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(seg0Seq)           // cum 不推进
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(sackOpt)
                .build());
        harness.channel().runPendingTasks();

        // seg1 + seg2 被 SACK 吸收
        assertThat(sender.sackedOut())
                .as("seg1 + seg2 tagged SACKED")
                .isEqualTo(2);
        // rackMstamp 已被 SACK tag 刷到最新段的发送时戳
        assertThat(sender.rackMstamp())
                .as("rackMstamp advanced by SACK")
                .isGreaterThan(0L);

        // RACK 命中:seg0 sentUs 距 rackMstamp 50ms,远超 reoWnd(上限 = srtt>>3,
        // 一般 ≤ 数毫秒),seg0 被标 LOST → lostOut=1
        assertThat(sender.lostOut())
                .as("rackDetectLoss marks seg0 as LOST(sentUs < rackMstamp - reoWnd)")
                .isEqualTo(1);
    }

    // ---- helpers ----

    /** 单块 SACK option(NOP padded),12 字节(4 对齐)。 */
    private static byte[] sackOption1(int l, int r) {
        byte[] o = new byte[12];
        o[0] = 0x01;
        o[1] = 0x01;
        o[2] = 0x05;
        o[3] = 0x0A;
        o[4] = (byte) (l >>> 24);  o[5] = (byte) (l >>> 16);
        o[6] = (byte) (l >>> 8);   o[7] = (byte) l;
        o[8] = (byte) (r >>> 24);  o[9] = (byte) (r >>> 16);
        o[10] = (byte) (r >>> 8);  o[11] = (byte) r;
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
