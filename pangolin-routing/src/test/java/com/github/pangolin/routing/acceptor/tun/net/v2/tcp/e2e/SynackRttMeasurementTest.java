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
 * E2E:3WHS 完成时的首个 RTT 采样 —— 对齐 Linux
 * {@code tcp_synack_rtt_meas}(net/ipv4/tcp_input.c)。
 *
 * <p>场景:
 * <ol>
 *   <li>客户端 SYN;</li>
 *   <li>栈发 SYN-ACK,打戳 {@code tcp_rsk(req)->snt_synack};</li>
 *   <li>sleep 几毫秒(制造真实 RTT);</li>
 *   <li>客户端 ACK 完成 3WHS → {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Listener#synackRttMeas}
 *       读出 (now - sntSynackUs) 作为首个 RTT 样本喂 SRTT/RTTVAR + WinMinMax 滤波器。</li>
 * </ol>
 *
 * <p>关键不变量:
 * <ul>
 *   <li>3WHS 完成后 {@code sender.srttUs() > 0} —— 首样本已采集,
 *       {@code rtoMs()} 从此脱离 {@code SRTT=0} 的退化路径;</li>
 *   <li>Karn 规则:若 SYN-ACK 曾重传({@code num_retrans > 0})则不取样 ——
 *       单独在 {@link SynAckRetransmitTest} 覆盖,本测试只跑无重传分支。</li>
 * </ul>
 */
class SynackRttMeasurementTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

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
    @DisplayName("3WHS 完成后 srttUs > 0(synackRttMeas 喂了首个 RTT 样本)")
    void synackRttMeasFeedsFirstSample() throws InterruptedException {
        // SYN
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        int isn = synAck.tcpSeq();
        synAck.release();

        // 留 3ms 制造真实 RTT —— System.nanoTime 是 synackRttMeas 的时钟源
        // (Listener.java:242 的 (nanoTime()/1000 - sntSynackUs)),
        // 和 EmbeddedChannel 调度时钟无关,必须真实 sleep。
        Thread.sleep(3);

        // 最后一个 ACK 完成 3WHS,触发 synackRttMeas
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();

        CapturingInitializer.CapturingHandler h = initializer.handler();
        assertThat(h).as("3WHS completed, handler created").isNotNull();
        Sender sender = h.sock().sender();

        assertThat(sender.srttUs())
                .as("synackRttMeas should feed first RTT sample via child.addRttSample")
                .isPositive();
        // RFC 6298 初始化分支:首样本写入后 rttvar = sample / 2
        assertThat(sender.rttvarUs())
                .as("rttvar initialized to srtt/2 on first sample")
                .isPositive();
    }

    @Test
    @DisplayName("首样本后 rtoMs 脱离 SRTT=0 退化路径(真实 RTT 基础上算)")
    void rtoDerivedFromRealSrttAfterHandshake() throws InterruptedException {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        int isn = synAck.tcpSeq();
        synAck.release();

        Thread.sleep(3);

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();

        Sender sender = initializer.handler().sock().sender();

        // rtoMs 在 SRTT≠0 时应位于 [TCP_RTO_MIN, TCP_RTO_MAX] 之间且非默认初值 —— 本用例仅断言非零 /
        // 合理上限,避免对 RFC 6298 具体公式产生过强的点估计依赖。
        long rto = sender.rtoMs();
        assertThat(rto).as("rto positive after first sample").isPositive();
        assertThat(rto).as("rto not inflated past RTO_MAX").isLessThanOrEqualTo(120_000L);
    }
}
