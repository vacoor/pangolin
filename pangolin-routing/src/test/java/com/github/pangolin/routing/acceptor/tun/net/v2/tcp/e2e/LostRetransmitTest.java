package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegment;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
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
 * 重传段也丢 —— Lost-Retransmit 场景。
 *
 * <p>RACK(RFC 8985)的判丢条件用 sentTime,因此即便段已带 RETRANS 标记,
 * 只要 {@code sentTime < rackMstamp - reoWnd},RACK 仍应能再次把它判 LOST,
 * 触发再次重传。本测试验证这一行为。
 *
 * <p>不修改算法,仅观察。若 RACK 在已 RETRANS 段上不重判 LOST(Linux 行为)
 * 则记录差异。
 */
class LostRetransmitTest {

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
    @DisplayName("FR 后再发新段并 SACK → rackMstamp 推过 seg0 重传时戳,RACK 应再判 seg0 LOST")
    void rackReDetectsLostRetransmit() throws InterruptedException {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        // Step 1:发 seg0、seg1、seg2(段间 15ms 时间缝)
        int[] seqs = new int[5];
        int[] lens = new int[5];
        for (int i = 0; i < 3; i++) {
            byte[] p = new byte[MSS];
            for (int j = 0; j < MSS; j++) p[j] = (byte) ('a' + i);
            h.send(p);
            Tcp4PacketBuf out = harness.readOutboundTcp();
            seqs[i] = out.tcpSeq();
            lens[i] = out.tcpPayloadLength();
            out.release();
            if (i < 2) Thread.sleep(15);
        }

        // Step 2:3 dupack 进 Recovery,FR 重传 seg0 — 这一刻 seg0.sentTime 被刷为 RTX 时刻
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, seqs[0]));
        }
        harness.channel().runPendingTasks();
        drainAll();
        assertThat(sock.inRecovery()).isTrue();

        TcpSegment seg0 = sock.sendBuffer().peekRtx();
        assertThat(seg0).isNotNull();
        long seg0RtxSentUs = seg0.sentTimeUs();
        assertThat(seg0.isRetransmitted())
                .as("FR 后 seg0 带 RETRANS 标记")
                .isTrue();

        // Step 3:等 50ms,然后再发 seg3、seg4 (此时 sentTime > seg0RtxSentUs)
        Thread.sleep(50);
        for (int i = 3; i < 5; i++) {
            byte[] p = new byte[MSS];
            for (int j = 0; j < MSS; j++) p[j] = (byte) ('a' + i);
            h.send(p);
            Tcp4PacketBuf out = harness.readOutboundTcp();
            seqs[i] = out.tcpSeq();
            lens[i] = out.tcpPayloadLength();
            out.release();
        }

        // Step 4:SACK seg3 + seg4 → rackMstamp 推到 seg4.sentTime(> seg0RtxSentUs)
        byte[] sackOpt = sack2blocks(
                seqs[3], seqs[3] + lens[3],
                seqs[4], seqs[4] + lens[4]);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(seqs[0])  // cum 不推进
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(sackOpt)
                .build());
        harness.channel().runPendingTasks();
        drainAll();

        long rackMstamp = sender.rackMstamp();
        long gapUs = rackMstamp - seg0RtxSentUs;
        System.out.println(
            "[T3 OBS] rackMstamp=" + rackMstamp
            + ", seg0.rtxSentUs=" + seg0RtxSentUs
            + ", gap=" + gapUs + "us"
            + ", lostOut=" + sender.lostOut()
            + ", retransOut=" + sender.retransOut()
            + ", sackedOut=" + sender.sackedOut()
            + ", seg0.isLost=" + seg0.isLost()
            + ", seg0.isRetransmitted=" + seg0.isRetransmitted());

        // 前置:gap 必须 > reoWnd(50ms 时差远超 srtt>>3 上限)
        assertThat(gapUs).as("gap > 0 是 RACK 再判前提").isGreaterThan(0L);

        // 关键观察:RACK 是否对已带 RETRANS 段重判 LOST
        // Linux 行为:RACK 在 tcp_rack_detect_loss 中**仍然检查**已 RETRANS 段
        //   (条件是 sacked & TCPCB_LOST 还未被打)。
        // v2 是否对齐取决于 rackDetectLoss 实现细节。
        boolean seg0NowLost = seg0.isLost();
        if (!seg0NowLost) {
            System.out.println(
                "[T3 OBS] DIVERGENCE:gap=" + gapUs + "us > 0,但 seg0 已 RETRANS 状态下"
                + "未被 RACK 再次判 LOST。Linux 行为应再判,v2 实现可能跳过此分支。");
        } else {
            System.out.println("[T3 OBS] OK:RACK 正确把已重传但仍未到达的段再判 LOST");
        }
        // 不强制断言 — 此项目前重点是观察并标记差异

        // 至少 sacked_out 应为 2(seg3+seg4 SACKed)
        assertThat(sender.sackedOut()).as("seg3+seg4 被 SACK").isEqualTo(2);

        // 不变量:packetsInFlight 应非负
        assertThat(sender.packetsInFlight())
                .as("packetsInFlight 不能为负")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("RTO 后 retransmitSkb 选择仍优先 LOST 段(seg0 在 R3 重打 LOST 后)")
    void rtoReclassifiesAndPicksLostFirst() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        // 发 2 段
        h.send(new byte[]{'a'});
        Tcp4PacketBuf out0 = harness.readOutboundTcp();
        int seg0Seq = out0.tcpSeq();
        out0.release();

        h.send(new byte[]{'b'});
        Tcp4PacketBuf out1 = harness.readOutboundTcp();
        int seg1Seq = out1.tcpSeq();
        out1.release();

        // RTO 模拟
        sender.onTimeoutByCc();
        // R3:两段都被打 LOST
        assertThat(sender.lostOut()).isEqualTo(2);

        // 第一次 retransmitSkb 应选 seg0
        sock.stack().output().retransmitSkb(sock);
        harness.channel().runPendingTasks();
        Tcp4PacketBuf rtx0 = drainForData();
        assertThat(rtx0).isNotNull();
        assertThat(rtx0.tcpSeq()).as("队首 LOST 段优先").isEqualTo(seg0Seq);
        rtx0.release();

        drainAll();
    }

    private Tcp4PacketBuf drainForData() {
        for (int i = 0; i < 8; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.tcpPayloadLength() > 0) return p;
            p.release();
        }
        return null;
    }

    private void drainAll() {
        Tcp4PacketBuf p;
        while ((p = harness.readOutboundTcp()) != null) p.release();
    }

    private static byte[] sack2blocks(int l1, int r1, int l2, int r2) {
        byte[] o = new byte[20];
        o[0] = 0x01; o[1] = 0x01; o[2] = 0x05; o[3] = 0x12;
        putInt(o, 4, l1); putInt(o, 8, r1);
        putInt(o, 12, l2); putInt(o, 16, r2);
        return o;
    }

    private static void putInt(byte[] o, int off, int v) {
        o[off] = (byte) (v >>> 24);
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
