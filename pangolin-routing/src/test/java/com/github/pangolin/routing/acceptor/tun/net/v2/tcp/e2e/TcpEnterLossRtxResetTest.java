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
 * 对齐 Linux {@code tcp_enter_loss}(net/ipv4/tcp_input.c)末尾的 rtx 队列扫描语义:
 * <ul>
 *   <li>所有段一律清 {@code TCPCB_SACKED_RETRANS} + {@code TCPCB_LOST}(LOST_MASK);</li>
 *   <li>非 {@code TCPCB_SACKED_ACKED} 段重新打 {@code TCPCB_LOST};</li>
 *   <li>{@code lost_out} 重计为新 LOST 段数量,{@code retrans_out} 归零。</li>
 * </ul>
 *
 * <p>不涉及检测 / CC 算法变化 — 只验证 RTO 进 Loss 时段标记和会计字段的重置。
 */
class TcpEnterLossRtxResetTest {

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
    @DisplayName("RTO 进 Loss → rtx 段清 RETRANS 重打 LOST,lost_out 重计,retrans_out 归零")
    void rtoEntryRewritesRtxFlagsAndCounters() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        // 发一段 payload
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        int origSeq = origOut.tcpSeq();
        origOut.release();

        // 触发 fast retransmit:让 retrans_out 涨到 1,且段带 RETRANS 标记
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(sender.retransOut()).as("FR 后 retrans_out > 0").isGreaterThan(0);

        TcpSegment head = sock.sendBuffer().peekRtx();
        assertThat(head).as("rtx 队列首段").isNotNull();
        assertThat(head.isRetransmitted())
                .as("FR 后队首段带 TCPCB_SACKED_RETRANS")
                .isTrue();

        // 触发 onTimeoutByCc(直接调,免去等真实 RTO timer)
        sender.onTimeoutByCc();

        // R3 验证 1:retrans_out 归零
        assertThat(sender.retransOut())
                .as("R3:tcp_enter_loss → retrans_out = 0")
                .isZero();

        // R3 验证 2:rtx 队列里非 SACK_ACKED 段被打 LOST,且 RETRANS 被清
        head = sock.sendBuffer().peekRtx();
        assertThat(head).isNotNull();
        assertThat(head.isRetransmitted())
                .as("R3:rtx 段 TCPCB_SACKED_RETRANS 被清")
                .isFalse();
        assertThat(head.isSackAcked())
                .as("非 SACK_ACKED 段")
                .isFalse();
        assertThat(head.isLost())
                .as("R3:非 SACK_ACKED 段重新打 TCPCB_LOST")
                .isTrue();

        // R3 验证 3:lost_out 重计为新 LOST 段数(1 段)
        assertThat(sender.lostOut())
                .as("R3:lost_out 重计为 rtx 中非 SACK_ACKED 段数")
                .isEqualTo(1);

        // R3 验证 4:此时再次 retransmitSkb,首次重传守卫(!isRetransmitted)应能再次触发 retrans_out++
        sock.stack().output().retransmitSkb(sock);
        harness.channel().runPendingTasks();
        drainAll();
        assertThat(sender.retransOut())
                .as("R3:RTO 后首次重传 → retrans_out 重新 ++(原本 R1-only 时这里恒为 0)")
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
