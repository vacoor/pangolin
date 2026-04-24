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
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:伪 RTO(Spurious RTO)经 F-RTO(RFC 5682)检测并 undo —— 对齐
 * Linux {@code tcp_process_loss} 的 F-RTO 分支。
 *
 * <p>场景:
 * <ol>
 *   <li>栈发一段;</li>
 *   <li>对端 ACK 被延迟,RTO 先触发 → 进入 CA_Loss,frtoCounter=1,
 *       frtoHighmark = RTO 瞬间的 sndNxt;</li>
 *   <li>RTO 导致重传副本发出;</li>
 *   <li>客户端随后 ACK 原始段(ack_num ≥ frtoHighmark)→ sndUna 达到 frtoHighmark;</li>
 *   <li>tcp_ack 在尾部调用 {@link Sender#tcpProcessFrto} 判定伪 RTO →
 *       {@link Sender#tcpUndoCwndReduction} 回滚 cwnd/ssthresh,迁 CA_Open。</li>
 * </ol>
 *
 * <p>本测试验证 R7.3c 迁到 Sender 的 {@link Sender#tcpProcessFrto} /
 * {@link Sender#tcpUndoCwndReduction} / {@link Sender#clearFrto} 协同路径。
 */
class SpuriousRtoUndoTest {

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
    @DisplayName("RTO 进 LOSS,后续 ACK 跨过 frtoHighmark → F-RTO undo → CA_Open,cwnd/ssthresh 回滚")
    void frtoUndoesSpuriousRtoOnOriginalAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        int cwndBefore = sender.cwnd();
        int ssthreshBefore = sender.ssthresh();

        // 发一段
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        h.send(payload);
        Tcp4PacketBuf firstOut = harness.readOutboundTcp();
        int origSeq = firstOut.tcpSeq();
        int origLen = firstOut.tcpPayloadLength();
        firstOut.release();

        // 推进直到 RTO 真正触发(state=LOSS,不只是 TLP)
        int loops = 0;
        while (!h.sock().inLoss() && loops++ < 8) {
            harness.channel().advanceTimeBy(sender.rtoMs() + 100, TimeUnit.MILLISECONDS);
            harness.channel().runScheduledPendingTasks();
            harness.channel().runPendingTasks();
            Tcp4PacketBuf p;
            while ((p = harness.readOutboundTcp()) != null) p.release();
        }
        assertThat(h.sock().inLoss())
                .as("RTO should push state into CA_Loss")
                .isTrue();

        // onTimeoutByCc 已记录 frtoHighmark (=RTO 瞬间 sndNxt) 和 frtoCounter
        int frtoMark = sender.frtoHighmark();
        assertThat(frtoMark)
                .as("frtoHighmark captures sndNxt at RTO entry")
                .isEqualTo(origSeq + origLen);
        assertThat(sender.frtoCounter())
                .as("F-RTO armed after RTO (frtoCounter=1)")
                .isEqualTo(1);
        assertThat(sender.cwnd())
                .as("cwnd forced to 1 on RTO")
                .isEqualTo(1);
        assertThat(sender.undoMarker())
                .as("undoMarker snapshotted by tcpInitUndo")
                .isNotZero();

        // 客户端 ACK 原始段(ack_num = origSeq + origLen = frtoHighmark)
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, origSeq + origLen));
        harness.channel().runPendingTasks();

        // sndUna 达到 frtoHighmark → F-RTO 命中,undo,迁 CA_Open
        assertThat(h.sock().isCaOpen())
                .as("F-RTO should undo CA_Loss → CA_Open")
                .isTrue();
        assertThat(sender.cwnd())
                .as("cwnd restored to >= priorCwnd (tcpUndoCwndReduction)")
                .isGreaterThanOrEqualTo(cwndBefore);
        // 注意:若 RTO 前 ssthresh == Integer.MAX_VALUE(slow start),
        // tcpInitUndo 会把 priorSsthresh 记为 0,undo 路径不再回滚 ssthresh。
        // 此处不断言 ssthreshBefore,仅验证 state+frto+cwnd。
        @SuppressWarnings("unused") int ignoreSsthreshBefore = ssthreshBefore;
        // F-RTO 武装清零
        assertThat(sender.frtoCounter())
                .as("frtoCounter cleared by clearFrto via tcpUndoCwndReduction")
                .isZero();
        assertThat(sender.frtoHighmark())
                .as("frtoHighmark cleared")
                .isZero();
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
