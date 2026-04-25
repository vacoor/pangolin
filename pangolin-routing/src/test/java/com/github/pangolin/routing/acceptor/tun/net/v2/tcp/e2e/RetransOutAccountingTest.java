package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
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
 * 对齐 Linux {@code tp->retrans_out}(net/ipv4/tcp_input.c / tcp_output.c)的会计行为:
 * <ul>
 *   <li>{@code __tcp_retransmit_skb} 首次给段打 {@code TCPCB_SACKED_RETRANS} 时
 *       {@code retrans_out += pcount};</li>
 *   <li>{@code tcp_clean_rtx_queue} 累计 ACK 释放带 RETRANS 标记的段时
 *       {@code retrans_out -= pcount};</li>
 *   <li>{@code tcp_packets_in_flight} 公式包含 {@code + retrans_out}。</li>
 * </ul>
 *
 * <p>本测试仅检验"会计字段维护",不涉及丢包检测算法 / CC 算法行为。
 */
class RetransOutAccountingTest {

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
    @DisplayName("Fast Retransmit → retrans_out++,packetsInFlight 公式包含 retrans_out")
    void fastRetransmitIncrementsRetransOut() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        assertThat(origOut).isNotNull();
        int origSeq = origOut.tcpSeq();
        origOut.release();

        assertThat(sender.retransOut())
                .as("初始(无重传)retrans_out = 0")
                .isZero();

        int packetsOutBefore = sender.packetsOut();
        int inflightBefore = sender.packetsInFlight();

        // 3 个 dupack 触发 fast retransmit
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();

        // 找到 fast retransmit 出口段
        Tcp4PacketBuf fastRtx = drainForSeq(origSeq);
        assertThat(fastRtx).as("fast retransmit 段应已发出").isNotNull();
        fastRtx.release();
        drainAll();

        // R1 验证点 1:retrans_out 增到 1(同一段重传一次)
        assertThat(sender.retransOut())
                .as("R1:首次重传段 → retrans_out = 1")
                .isEqualTo(1);

        // R1 验证点 2:packetsInFlight 公式 = packets_out - sacked_out - lost_out + retrans_out
        int expected = sender.packetsOut() - sender.sackedOut() - sender.lostOut() + sender.retransOut();
        assertThat(sender.packetsInFlight())
                .as("R1:packetsInFlight 公式与 Linux 一致(含 +retrans_out)")
                .isEqualTo(Math.max(0, expected));
    }

    @Test
    @DisplayName("累计 ACK 吃掉重传段 → retrans_out--")
    void cumulativeAckOnRetransSegmentDecrementsRetransOut() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        int origSeq = origOut.tcpSeq();
        int payloadLen = origOut.tcpPayloadLength();
        origOut.release();

        // 触发 fast retransmit
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(sender.retransOut()).as("FR 后 retrans_out = 1").isEqualTo(1);

        // 对端发送累计 ACK 覆盖整个段
        int ackCovering = origSeq + payloadLen;
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, ackCovering));
        harness.channel().runPendingTasks();
        drainAll();

        // R1 验证点 3:累计 ACK 把重传段吃掉 → retrans_out 退回 0
        assertThat(sender.retransOut())
                .as("R1:累计 ACK 释放 RETRANS 段 → retrans_out--")
                .isZero();
    }

    @Test
    @DisplayName("RTO 进入 Loss → retrans_out 清零(对齐 tcp_enter_loss)")
    void rtoEntryClearsRetransOut() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        int origSeq = origOut.tcpSeq();
        origOut.release();

        // 触发 fast retransmit,让 retrans_out > 0
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();
        drainAll();
        assertThat(sender.retransOut()).as("FR 后 retrans_out > 0").isGreaterThan(0);

        // 直接调 onTimeoutByCc 模拟 RTO 进入 Loss(免去触发真实 timer 的 wall-clock 等待)
        sender.onTimeoutByCc();

        // R1 验证点 4:对齐 Linux tcp_enter_loss 的 retrans_out = 0
        assertThat(sender.retransOut())
                .as("R1:tcp_enter_loss → retrans_out = 0")
                .isZero();
    }

    // ---- helpers (与 FastRecoveryTest 同形) ----

    private Tcp4PacketBuf drainForSeq(int expected) {
        for (int i = 0; i < 8; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.tcpSeq() == expected && p.tcpPayloadLength() > 0) {
                return p;
            }
            p.release();
        }
        return null;
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
