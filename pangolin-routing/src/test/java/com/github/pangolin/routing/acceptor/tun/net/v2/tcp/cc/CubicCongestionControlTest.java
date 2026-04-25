package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc;

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
 * 验证 {@link CubicCongestionControl}(RFC 9438)接入 SPI 后的核心行为:
 * <ol>
 *   <li>装载 CUBIC 实例后,sock 的 onAckedByCc 通过 SPI 走 CUBIC 算法</li>
 *   <li>Slow Start:cwnd 按 ackedPackets 增长(与 NewReno 同形)</li>
 *   <li>Loss reaction:ssthresh = cwnd × 0.7(BETA)</li>
 *   <li>Fast Retransmit 入口:cwnd = ssthresh + 3(SPI onStateChange)</li>
 *   <li>RTO 进 Loss:cwnd = 1(SPI onStateChange)</li>
 * </ol>
 */
class CubicCongestionControlTest {

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
        // 握手完成后装 CUBIC
        TcpSock sock = initializer.handler().sock();
        CubicCongestionControl cubic = new CubicCongestionControl();
        sock.sender().congestionControl(cubic);
        cubic.init(sock);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("CUBIC slow start:每个 advanced ACK cwnd += newlyAcked")
    void slowStartGrowsCwnd() {
        TcpSock sock = initializer.handler().sock();
        Sender sender = sock.sender();
        int initCwnd = sender.cwnd();
        int initSs = sender.ssthresh();
        assertThat(initCwnd).as("初始 cwnd > 0").isGreaterThan(0);
        assertThat(initSs).as("初始 ssthresh = INT_MAX").isEqualTo(Integer.MAX_VALUE);

        // 发一段数据,客户端 ACK 推 sndUna 让 cwnd 走 slow start
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        initializer.handler().send(payload);
        Tcp4PacketBuf out = harness.readOutboundTcp();
        int seq = out.tcpSeq();
        int len = out.tcpPayloadLength();
        out.release();
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, seq + len));
        harness.channel().runPendingTasks();

        assertThat(sender.cwnd())
                .as("CUBIC slow start:cwnd 增长")
                .isGreaterThan(initCwnd);
    }

    @Test
    @DisplayName("CUBIC ssthresh:进 Recovery 时 ssthresh = cwnd × 0.7,cwnd 入口不变(PRR)")
    void recoveryHalvesSsthreshByBeta() {
        TcpSock sock = initializer.handler().sock();
        Sender sender = sock.sender();
        // 发一段数据
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        initializer.handler().send(payload);
        Tcp4PacketBuf out = harness.readOutboundTcp();
        int seq = out.tcpSeq();
        out.release();

        int cwndBefore = sender.cwnd();
        int expectedSs = Math.max((int) (cwndBefore * 0.7), 2);

        // 3 dupack 触发 FR
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, seq));
        }
        harness.channel().runPendingTasks();

        assertThat(sock.inRecovery()).as("3 dupacks → Recovery").isTrue();
        assertThat(sender.ssthresh())
                .as("CUBIC ssthresh = cwnd × 0.7(BETA)")
                .isEqualTo(expectedSs);
        // PRR(对齐 Linux tcp_init_cwnd_reduction):入口 cwnd 不变,
        // 由 Prr.onAck 在每个 ACK 平滑下降到 ssthresh
        assertThat(sender.cwnd())
                .as("PRR: cwnd stays at prior_cwnd on Recovery entry")
                .isEqualTo(cwndBefore);
        assertThat(sender.prrDelivered()).as("prrDelivered cleared at entry").isZero();
    }

    @Test
    @DisplayName("CUBIC RTO:onStateChange(LOSS) 把 cwnd 设为 1")
    void rtoSetsCwndToOne() {
        TcpSock sock = initializer.handler().sock();
        Sender sender = sock.sender();
        // 发一段
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        initializer.handler().send(payload);
        Tcp4PacketBuf out = harness.readOutboundTcp();
        out.release();

        // 直接调 onTimeoutByCc 模拟 RTO
        sender.onTimeoutByCc();

        assertThat(sock.inLoss()).isTrue();
        assertThat(sender.cwnd())
                .as("CUBIC onStateChange(LOSS) → cwnd = 1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CUBIC undoCwnd = max(priorCwnd, cwnd) — 不让自然增长被压回")
    void undoCwndPicksMax() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        CubicCongestionControl cc =
                (CubicCongestionControl) s.congestionControl();

        s.cwnd(80);
        s.priorCwnd(60);
        assertThat(cc.undoCwnd(sock)).as("cwnd > priorCwnd → 取 cwnd").isEqualTo(80);

        s.cwnd(40);
        s.priorCwnd(60);
        assertThat(cc.undoCwnd(sock)).as("priorCwnd > cwnd → 取 priorCwnd").isEqualTo(60);
    }

    @Test
    @DisplayName("CUBIC 退出 Recovery:cwnd = ssthresh(NewReno 风格收尾)")
    void recoveryExitDeflatesCwnd() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        CubicCongestionControl cc =
                (CubicCongestionControl) s.congestionControl();

        // 模拟 Recovery 期 inflated cwnd
        s.cwnd(15);
        s.ssthresh(7);
        s.congestionState(TcpSock.CongestionState.OPEN);
        cc.onStateChange(sock, TcpSock.CongestionState.RECOVERY,
                TcpSock.CongestionState.OPEN);

        assertThat(s.cwnd())
                .as("退出 Recovery cwnd = ssthresh")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("CUBIC pacingRateBps 默认 0(不 pace)")
    void cubicDoesNotPaceByDefault() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        CubicCongestionControl cc =
                (CubicCongestionControl) s.congestionControl();
        assertThat(cc.pacingRateBps(sock)).as("CUBIC 不 pace").isZero();
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
