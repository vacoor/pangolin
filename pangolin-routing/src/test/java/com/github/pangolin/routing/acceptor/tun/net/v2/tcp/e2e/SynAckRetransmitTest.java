package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:TcpHandshaker 对半连接(SYN_RECV)的 SYN-ACK 重传 —— 对齐 Linux
 * {@code tcp_synack_rtx_timer}。
 *
 * <p>场景:客户端 SYN 到达后栈发 SYN-ACK,但客户端永不 ACK。
 * {@code TcpHandshaker.scheduleRetransmit} 按 {@code SYNACK_RTO_INIT_MS = 1000ms}
 * 指数退避调度重传:1s, 2s, 4s, ... 直到 {@code MAX_SYNACK_RETRIES = 5} 后放弃。
 *
 * <p>覆盖:
 * <ul>
 *   <li>初次 SYN-ACK 发出后调度 1s 重传 timer;</li>
 *   <li>1s + 余量推进 → 出站第 2 个 SYN-ACK(seq 不变 = server ISN,{@code num_retrans}+1);</li>
 *   <li>再次 advance 2s 后出站第 3 个 SYN-ACK(指数退避:1s → 2s)。</li>
 * </ul>
 */
class SynAckRetransmitTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    private static final long SYN_ACK_RTO_MS = 1000L;

    private TcpStackHarness harness;
    private CapturingInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CapturingInitializer();
        harness = new TcpStackHarness(initializer);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("客户端永不 ACK → 初次 SYN-ACK 后,1s 出第 2 个,再 2s 出第 3 个(指数退避)")
    void synAckRetransmittedOnTimerExpiry() {
        // Step 1: 客户端 SYN
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        harness.channel().runPendingTasks();

        // Step 2: 读初次 SYN-ACK
        Tcp4PacketBuf synAck1 = harness.readOutboundTcp();
        assertThat(synAck1).as("initial SYN-ACK").isNotNull();
        int serverIsn;
        try {
            assertThat(synAck1.isSyn() && synAck1.isAck()).isTrue();
            serverIsn = synAck1.tcpSeq();
            assertThat(synAck1.tcpAckNum())
                    .as("SYN-ACK acks CLIENT_ISN+1")
                    .isEqualTo(CLIENT_ISN + 1);
        } finally {
            synAck1.release();
        }

        // 客户端不发最后 ACK,半连接保持
        assertThat(initializer.handler())
                .as("handshake incomplete — no CapturingHandler yet")
                .isNull();

        // Step 3: 推进 1s + 100ms 余量,触发首次 SYN-ACK 重传
        harness.channel().advanceTimeBy(SYN_ACK_RTO_MS + 100, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        Tcp4PacketBuf synAck2 = harness.readOutboundTcp();
        assertThat(synAck2).as("first SYN-ACK retransmit after 1s").isNotNull();
        try {
            assertThat(synAck2.isSyn() && synAck2.isAck()).isTrue();
            // seq 保持 server ISN(同一 SYN-ACK 的重传)
            assertThat(synAck2.tcpSeq())
                    .as("retransmitted SYN-ACK uses same ISN")
                    .isEqualTo(serverIsn);
        } finally {
            synAck2.release();
        }

        // Step 4: 再推进 2s + 余量,指数退避(1s → 2s)→ 第 2 次重传
        harness.channel().advanceTimeBy(2 * SYN_ACK_RTO_MS + 100, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        Tcp4PacketBuf synAck3 = harness.readOutboundTcp();
        assertThat(synAck3).as("second SYN-ACK retransmit after exponential backoff").isNotNull();
        try {
            assertThat(synAck3.tcpSeq()).isEqualTo(serverIsn);
        } finally {
            synAck3.release();
        }
    }

    @Test
    @DisplayName("客户端在第 1 次重传前 ACK → 握手完成,后续不再重传 SYN-ACK")
    void clientAckCancelsSynAckRetransmit() {
        // SYN → SYN-ACK
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        int serverIsn = synAck.tcpSeq();
        synAck.release();

        // 客户端在首 RTO 前(200ms)ACK
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));
        harness.channel().runPendingTasks();

        // 握手完成
        assertThat(initializer.handler())
                .as("handshake completed, CapturingHandler attached")
                .isNotNull();

        // 推进超过 1s:不应再有 SYN-ACK 重传(timer 已 cancel)
        harness.channel().advanceTimeBy(SYN_ACK_RTO_MS + 500, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        Tcp4PacketBuf stale = harness.readOutboundTcp();
        if (stale != null) {
            try {
                assertThat(stale.isSyn())
                        .as("no SYN-ACK retransmit after handshake completed")
                        .isFalse();
            } finally {
                stale.release();
            }
        }
    }
}
