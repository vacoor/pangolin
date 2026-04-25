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

import java.nio.charset.StandardCharsets;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同段多次重传压力 —— 验证 retrans_out / undo_retrans / RETRANS 标记
 * 在同一段被重传多次时的会计正确性。
 *
 * <p>主要场景:
 * <ul>
 *   <li>FR 重传一次,RTO 又触发同段重传(进 Loss 时 RETRANS 被清,RTO 重传后 retrans_out 重新计入);</li>
 *   <li>RTO 退避期间多次重传,retrans_out 不应无限上涨。</li>
 * </ul>
 */
class MultiRetransSegmentTest {

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
    @DisplayName("FR 重传后 RTO 又重传同段 → R3 路径下 retrans_out 重新累计 (1→0→1),不爆")
    void frThenRtoOnSameSegment() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        int origSeq = origOut.tcpSeq();
        origOut.release();

        // Phase 1: 触发 FR (3 dupacks)
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(sock.inRecovery()).as("FR → Recovery").isTrue();
        assertThat(sender.retransOut()).as("FR 重传 → retrans_out=1").isEqualTo(1);

        TcpSegment skb = sock.sendBuffer().peekRtx();
        assertThat(skb).isNotNull();
        assertThat(skb.isRetransmitted()).as("段带 RETRANS 标记").isTrue();
        assertThat(sender.undoRetrans())
                .as("FR 后 undoRetrans=1(本 Recovery epoch 内)")
                .isEqualTo(1);

        // Phase 2: 模拟 RTO 触发(直接调 onTimeoutByCc)
        sender.onTimeoutByCc();

        assertThat(sock.inLoss()).as("onTimeoutByCc → Loss").isTrue();
        // R3 路径:rtx 队列扫 + 清 RETRANS + 重打 LOST + retrans_out=0
        assertThat(sender.retransOut()).as("R3:enter_loss 清零 retrans_out").isZero();
        assertThat(skb.isRetransmitted()).as("R3:RETRANS 标记被清").isFalse();
        assertThat(skb.isLost()).as("R3:重新打 LOST").isTrue();
        // 对齐 Linux tcp_enter_loss → tcp_init_undo:每个 undo epoch 独立计数,
        // undoRetrans 在 Loss 入口被 tcpInitUndo 重置为 0,后续 RTO 重传重新累计。
        assertThat(sender.undoRetrans())
                .as("Loss 入口 tcp_init_undo 重置 undoRetrans=0(新 undo epoch)")
                .isZero();

        // Phase 3: RTO 后再次重传同段
        sock.stack().output().retransmitSkb(sock);
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(sender.retransOut())
                .as("R3 后:RTO 重传重新 ++ retrans_out=1")
                .isEqualTo(1);
        assertThat(skb.isRetransmitted())
                .as("段再次带 RETRANS 标记")
                .isTrue();
        assertThat(sender.undoRetrans())
                .as("Loss epoch 内 RTO 一次重传 → undoRetrans=1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Loss 内连续多次 retransmitSkb 同段 → retrans_out 不重复 ++(首次重传守卫生效)")
    void multipleRetransmitCallsInLossDontDoubleCount() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        origOut.release();

        // 直接进 Loss(模拟 RTO)
        sender.onTimeoutByCc();
        assertThat(sender.retransOut()).isZero();

        // 连续 3 次调 retransmitSkb 同段
        for (int i = 0; i < 3; i++) {
            sock.stack().output().retransmitSkb(sock);
            harness.channel().runPendingTasks();
            drainAll();
        }

        // retrans_out 应只 ++ 一次(首次重传守卫:!isRetransmitted())
        assertThat(sender.retransOut())
                .as("首次重传守卫:同段重复 retransmitSkb 只累加一次 retrans_out")
                .isEqualTo(1);
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
