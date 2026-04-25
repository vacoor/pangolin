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

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NewReno SPI 行为单元测试 — 直接调 {@link NewRenoCongestionControl} 的接口方法,
 * 验证 ssthresh / undoCwnd / onAck / onStateChange 公式。
 *
 * <p>e2e 场景由 {@code SlowStartTest} / {@code FastRecoveryTest} 等覆盖,本测试聚焦
 * 在 SPI 接口本身的契约。
 */
class NewRenoCongestionControlTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    private TcpStackHarness harness;
    private CapturingInitializer initializer;
    private int serverIsn;
    private NewRenoCongestionControl reno;

    @BeforeEach
    void setUp() {
        initializer = new CapturingInitializer();
        harness = new TcpStackHarness(initializer);
        serverIsn = completeHandshake();
        reno = NewRenoCongestionControl.INSTANCE;
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("ssthresh = max(cwnd / 2, 2)")
    void ssthreshFormula() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(100);
        assertThat(reno.ssthresh(sock)).isEqualTo(50);

        s.cwnd(3);
        assertThat(reno.ssthresh(sock)).isEqualTo(2);  // max(1, 2) = 2

        s.cwnd(2);
        assertThat(reno.ssthresh(sock)).isEqualTo(2);  // max(1, 2) = 2
    }

    @Test
    @DisplayName("undoCwnd = max(priorCwnd, cwnd) — 不让自然增长被压回")
    void undoCwndPicksMax() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();

        s.cwnd(80);
        s.priorCwnd(60);
        assertThat(reno.undoCwnd(sock)).as("cwnd > priorCwnd → 取 cwnd").isEqualTo(80);

        s.cwnd(40);
        s.priorCwnd(60);
        assertThat(reno.undoCwnd(sock)).as("priorCwnd > cwnd → 取 priorCwnd").isEqualTo(60);
    }

    @Test
    @DisplayName("onAck slow start:cwnd < ssthresh 时 cwnd += ackedPackets")
    void onAckSlowStartIncrement() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(10);
        s.ssthresh(100);

        RateSample rs = new RateSample();
        rs.ackedPackets = 3;
        rs.ackedBytes = 3 * 1460;

        reno.onAck(sock, rs);

        assertThat(s.cwnd()).as("slow start cwnd += 3").isEqualTo(13);
    }

    @Test
    @DisplayName("onAck CA:cwnd ≥ ssthresh 时按 caIncrCounter 累加,够 cwnd 段后 +1")
    void onAckCongestionAvoidance() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(10);
        s.ssthresh(5);          // cwnd ≥ ssthresh,进 CA
        s.caIncrCounter(0);

        RateSample rs = new RateSample();
        rs.ackedPackets = 1;
        rs.ackedBytes = 1460;

        // 9 次:caIncrCounter 累到 9,< cwnd=10,不 cwnd++
        for (int i = 0; i < 9; i++) {
            reno.onAck(sock, rs);
        }
        assertThat(s.cwnd()).as("CA 累计 9 次仍 = 10").isEqualTo(10);
        assertThat(s.caIncrCounter()).as("counter = 9").isEqualTo(9);

        // 第 10 次:counter=10 ≥ cwnd=10,cwnd++ counter=0
        reno.onAck(sock, rs);
        assertThat(s.cwnd()).as("CA 第 10 次 cwnd++").isEqualTo(11);
        assertThat(s.caIncrCounter()).as("counter 清零").isZero();
    }

    @Test
    @DisplayName("onAck RECOVERY:NewReno SPI 自身不动 cwnd(PRR 由 Sender 直接驱动)")
    void onAckInRecoveryIsNoOp() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(10);
        s.ssthresh(5);
        s.congestionState(TcpSock.CongestionState.RECOVERY);

        // dupack 形态(ackedPackets=0)
        RateSample dupack = new RateSample();
        reno.onAck(sock, dupack);
        assertThat(s.cwnd()).as("Recovery dupack:NewReno SPI 不动 cwnd").isEqualTo(10);

        // partial ACK 形态(ackedPackets=2)
        RateSample partial = new RateSample();
        partial.ackedPackets = 2;
        partial.ackedBytes = 2 * 1460;
        reno.onAck(sock, partial);
        assertThat(s.cwnd()).as("Recovery partial ACK:NewReno SPI 不动 cwnd").isEqualTo(10);
    }

    @Test
    @DisplayName("onAck LOSS:NewReno SPI 不动 cwnd(等状态退出后再走自然增长)")
    void onAckInLossIsNoOp() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(1);
        s.congestionState(TcpSock.CongestionState.LOSS);

        RateSample rs = new RateSample();
        rs.ackedPackets = 5;
        rs.ackedBytes = 5 * 1460;
        reno.onAck(sock, rs);

        assertThat(s.cwnd()).as("Loss 期 SPI 不动 cwnd").isEqualTo(1);
    }

    @Test
    @DisplayName("onStateChange OPEN→RECOVERY:NewReno 自身 no-op(PRR 重置由 Sender 直接调)")
    void onStateChangeRecoveryEntryIsCcNoOp() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(20);
        s.ssthresh(7);          // 已被 Sender 通过 cc.ssthresh() 设好
        s.congestionState(TcpSock.CongestionState.RECOVERY);

        reno.onStateChange(sock, TcpSock.CongestionState.OPEN, TcpSock.CongestionState.RECOVERY);

        // 对齐 Linux:cong_ops 的 set_state 不负责 cwnd,也不负责 PRR 计数器重置 —
        //   cwnd 由 PRR 在 ACK 路径渐降;prrDelivered/prrOut 重置由调用方
        //  (Sender)在 set_state 之前直接调 Prr.enterRecovery 完成。
        assertThat(s.cwnd()).as("NewReno.onStateChange(_, RECOVERY) 不动 cwnd").isEqualTo(20);
    }

    @Test
    @DisplayName("onStateChange ANY→LOSS:cwnd = 1")
    void onStateChangeLossEntrySetsCwndOne() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(50);
        s.congestionState(TcpSock.CongestionState.LOSS);

        reno.onStateChange(sock, TcpSock.CongestionState.OPEN, TcpSock.CongestionState.LOSS);
        assertThat(s.cwnd()).as("Loss 入口 cwnd=1").isEqualTo(1);
    }

    @Test
    @DisplayName("onStateChange RECOVERY→OPEN:cwnd = ssthresh(退出 Recovery)")
    void onStateChangeRecoveryExitDeflatesCwnd() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(15);             // Recovery 期 inflated
        s.ssthresh(7);
        s.congestionState(TcpSock.CongestionState.OPEN);

        reno.onStateChange(sock, TcpSock.CongestionState.RECOVERY, TcpSock.CongestionState.OPEN);
        assertThat(s.cwnd()).as("退出 Recovery cwnd=ssthresh").isEqualTo(7);
    }

    @Test
    @DisplayName("onStateChange OPEN↔DISORDER:不动 cwnd")
    void onStateChangeOpenDisorderNoOp() {
        TcpSock sock = initializer.handler().sock();
        Sender s = sock.sender();
        s.cwnd(20);
        int before = s.cwnd();

        s.congestionState(TcpSock.CongestionState.DISORDER);
        reno.onStateChange(sock, TcpSock.CongestionState.OPEN, TcpSock.CongestionState.DISORDER);
        assertThat(s.cwnd()).as("OPEN→DISORDER 不动 cwnd").isEqualTo(before);

        s.congestionState(TcpSock.CongestionState.OPEN);
        reno.onStateChange(sock, TcpSock.CongestionState.DISORDER, TcpSock.CongestionState.OPEN);
        assertThat(s.cwnd()).as("DISORDER→OPEN 不动 cwnd").isEqualTo(before);
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
