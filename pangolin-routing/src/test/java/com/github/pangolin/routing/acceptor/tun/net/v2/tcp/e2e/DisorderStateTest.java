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
 * 对齐 Linux {@code TCP_CA_Disorder}(net/ipv4/tcp_input.c)状态:
 * <ul>
 *   <li>{@code OPEN} 收到第 1 个 dup ACK → 进 {@code DISORDER}(cwnd / ssthresh 不动);</li>
 *   <li>{@code DISORDER} 内继续收 dup ACK → 累计计数,状态保持;</li>
 *   <li>{@code DISORDER} 在第 3 个 dup ACK → 升级到 {@code RECOVERY};</li>
 *   <li>{@code DISORDER} 期间 {@code SND.UNA} 推进 → 退回 {@code OPEN}(乱序而非丢包)。</li>
 * </ul>
 *
 * <p>同时验证 RFC 3042 Limited Transmit 在 {@code DISORDER} 仍生效(原本只在 OPEN)。
 */
class DisorderStateTest {

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
    @DisplayName("OPEN + 第 1 个 dupack → DISORDER;cwnd / ssthresh 不动")
    void firstDupackEntersDisorder() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);
        Tcp4PacketBuf out = harness.readOutboundTcp();
        int origSeq = out.tcpSeq();
        out.release();

        int cwndBefore = sender.cwnd();
        int ssthreshBefore = sender.ssthresh();

        assertThat(sock.isCaOpen()).as("初始 OPEN").isTrue();

        // 第 1 个 dupack
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, origSeq));
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(sock.inDisorder())
                .as("R4:OPEN + 1st dupack → DISORDER")
                .isTrue();
        assertThat(sock.isCaOpen())
                .as("DISORDER 不再算 OPEN")
                .isFalse();
        assertThat(sender.cwnd())
                .as("DISORDER 状态本身不动 cwnd")
                .isEqualTo(cwndBefore);
        assertThat(sender.ssthresh())
                .as("DISORDER 状态本身不动 ssthresh")
                .isEqualTo(ssthreshBefore);
    }

    @Test
    @DisplayName("DISORDER + 第 3 个 dupack → RECOVERY")
    void thirdDupackInDisorderEntersRecovery() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);
        Tcp4PacketBuf out = harness.readOutboundTcp();
        int origSeq = out.tcpSeq();
        out.release();

        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(sock.inRecovery())
                .as("R4:DISORDER + 第 3 个 dupack 升级到 RECOVERY")
                .isTrue();
        assertThat(sock.inDisorder())
                .as("已离开 DISORDER")
                .isFalse();
    }

    @Test
    @DisplayName("DISORDER + sndUna 推进 → 回退 OPEN(乱序非丢包)")
    void disorderExitsOnSndUnaAdvance() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);
        Tcp4PacketBuf out = harness.readOutboundTcp();
        int origSeq = out.tcpSeq();
        int payloadLen = out.tcpPayloadLength();
        out.release();

        // 1 个 dupack 进 DISORDER
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, origSeq));
        harness.channel().runPendingTasks();
        drainAll();
        assertThat(sock.inDisorder()).isTrue();

        // 累计 ACK 推进 sndUna(说明只是 reorder)
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, origSeq + payloadLen));
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(sock.isCaOpen())
                .as("R4:DISORDER + sndUna 推进 → 回退 OPEN")
                .isTrue();
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
