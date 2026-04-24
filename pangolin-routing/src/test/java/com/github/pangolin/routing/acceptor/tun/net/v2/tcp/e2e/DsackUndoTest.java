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
 * E2E:DSACK-driven undo(RFC 2883)—— 对齐 Linux {@code tcp_try_undo_dsack}。
 * 进入 CA_Recovery 后的 Fast Retransmit 副本被 DSACK 完全抵消时,触发 undo 回滚。
 *
 * <p>场景链:
 * <ol>
 *   <li>栈发 seg0 + seg1;</li>
 *   <li>客户端 3 次 dup ACK(cum=seg0.seq,不推进)→ 栈进 CA_Recovery,
 *       Fast Retransmit seg0,{@code undoMarker=seg0.seq},{@code undoRetrans=1},
 *       {@code retransStamp!=0};</li>
 *   <li>客户端 ACK cum=seg1.seq + SACK [seg1.seq, seg1.end) → sndUna 前进到 seg0.end
 *       (seg1 仍在空洞/SACK);此时 Recovery 条件不满足退出(sndUna &lt; highSeq);</li>
 *   <li>客户端再发 dup-ACK + DSACK [seg0.seq, seg0.end) → fe ≤ priorSndUna 匹配
 *       DSACK Case 1,{@code undoRetrans} 递减到 0;</li>
 *   <li>本轮 tcpAck 尾部 {@code tcpTryUndoDsack} 命中:state=CA_Recovery,
 *       undoRetrans=0,undoMarker 有效 → undo → 迁 CA_Open,cwnd/ssthresh 回滚。</li>
 * </ol>
 *
 * <p>本测试是 R7.3c 迁到 Sender 的 {@code tcpTryUndoDsack} 的端到端行为回归。
 */
class DsackUndoTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;
    /** 默认 MSS。与 TcpConfig.TCP_MSS_DEFAULT 对齐 —— 段用 MSS 大小避免
     *  tcp_collapse_retrans 把两段合并成一段导致 packetsOut 计数失真。 */
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
    @DisplayName("RECOVERY 中 DSACK 抵消重传副本 → tcpTryUndoDsack 命中,迁 CA_Open")
    void dsackFullyAbsorbsRetransTriggersUndo() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        int priorCwnd = sender.cwnd();

        // Step 1:发 seg0 + seg1(MSS 大小 —— 避开 tcp_collapse_retrans:
        // FR 路径仅当段 < MSS 时尝试与 next 合并,combinedLen > mss 会被拒)
        byte[] p0 = new byte[MSS]; for (int i = 0; i < MSS; i++) p0[i] = 'a';
        h.send(p0);
        Tcp4PacketBuf s0 = harness.readOutboundTcp();
        int seg0Seq = s0.tcpSeq();
        int seg0Len = s0.tcpPayloadLength();
        s0.release();

        byte[] p1 = new byte[MSS]; for (int i = 0; i < MSS; i++) p1[i] = 'b';
        h.send(p1);
        Tcp4PacketBuf s1 = harness.readOutboundTcp();
        int seg1Seq = s1.tcpSeq();
        int seg1Len = s1.tcpPayloadLength();
        s1.release();

        // Step 2:3 dup ACKs(cum=seg0.seq 不推进)→ Fast Retransmit
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, seg0Seq));
        }
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(h.sock().inRecovery())
                .as("3 dupacks → CA_Recovery")
                .isTrue();
        assertThat(sender.undoMarker())
                .as("undoMarker = seg0.seq at Recovery entry")
                .isEqualTo(seg0Seq);
        assertThat(sender.undoRetrans())
                .as("retransmitSkb increments undoRetrans")
                .isEqualTo(1);
        assertThat(sender.retransStamp())
                .as("retransStamp set at first retransmit")
                .isNotZero();

        // Step 3:cum ACK 到 seg0.end + SACK seg1 → sndUna 推进,但 sndUna<highSeq 不退 Recovery
        byte[] sackSeg1 = sackOption1(seg1Seq, seg1Seq + seg1Len);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(seg0Seq + seg0Len)
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(sackSeg1)
                .build());
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(h.sock().inRecovery())
                .as("still in Recovery (sndUna < highSeq)")
                .isTrue();
        assertThat(sender.sndUna())
                .as("sndUna advanced to seg0.end")
                .isEqualTo(seg0Seq + seg0Len);

        // Step 4:dup ACK(不推进)+ DSACK [seg0.seq, seg0.end) —— fe=seg0.end ≤
        // priorSndUna=seg0.end,触发 DSACK Case 1;undoRetrans 递减到 0 后
        // tcpAck 尾部 tcpTryUndoDsack 命中
        byte[] dsackOpt = sackOption1(seg0Seq, seg0Seq + seg0Len);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(seg0Seq + seg0Len)  // 同 step 3,不推进
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(dsackOpt)
                .build());
        harness.channel().runPendingTasks();

        // undo 触发后迁 CA_Open
        assertThat(h.sock().isCaOpen())
                .as("DSACK absorbed all retransmits → tcpTryUndoDsack undoes to CA_Open")
                .isTrue();
        assertThat(sender.undoRetrans())
                .as("undoRetrans decremented by DSACK to 0")
                .isZero();
        assertThat(sender.cwnd())
                .as("cwnd restored to at least priorCwnd")
                .isGreaterThanOrEqualTo(priorCwnd);
        assertThat(sender.undoMarker())
                .as("undoMarker cleared by tcpUndoCwndReduction")
                .isZero();
    }

    // ---- helpers ----

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

    private void drainAll() {
        Tcp4PacketBuf p;
        while ((p = harness.readOutboundTcp()) != null) p.release();
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
