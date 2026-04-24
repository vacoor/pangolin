package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
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
 * E2E:零窗探测(zero-window probe,对齐 Linux {@code tcp_probe_timer} /
 * {@code sendProbe0})。
 *
 * <p>覆盖 R7.1 迁到 Sender 的 probe 家族字段(probeBackoffShift / probesOut /
 * probesTstampMs / userTimeoutMs)以及 {@link
 * com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender#armProbe0}
 * / {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender#probeTimer}
 * 的完整触发路径。
 *
 * <p>场景:
 * <ol>
 *   <li>3WHS 完成后,栈侧主动发一段数据;客户端 ACK 数据但通告 window=0,
 *       迫使栈进入零窗状态;</li>
 *   <li>栈继续有新 payload,进入 tcpSendHead 排队 —— 触发 {@code armProbe0} 武装定时器;</li>
 *   <li>advanceTimeBy 推进到探测基线后,栈出站一个探测段;</li>
 *   <li>{@code probesOut} 计数应增长;</li>
 *   <li>客户端回一个 ACK 带非零 window,栈把 probesOut 重置为 0(对齐
 *       {@code tcpAckProbe})。</li>
 * </ol>
 */
class ZeroWindowProbeTest {

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
    @DisplayName("客户端 ACK 带 window=0 且栈仍有新数据 → 安排 probe0 定时器,advanceTime 后出站探测段")
    void zeroWindowTriggersProbe0() {
        CapturingInitializer.CapturingHandler h = initializer.handler();

        // Step 1:栈侧发一段数据
        byte[] first = "A".getBytes(StandardCharsets.UTF_8);
        h.send(first);
        Tcp4PacketBuf firstOut = harness.readOutboundTcp();
        assertThat(firstOut).isNotNull();
        int firstSeq = firstOut.tcpSeq();
        int firstLen = firstOut.tcpPayloadLength();
        firstOut.release();

        // Step 2:客户端 ACK 且 window=0(迫使零窗)
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(firstSeq + firstLen)
                .flags(PacketFactory.TCP_FLAG_ACK)
                .window(0)
                .build());
        harness.channel().runPendingTasks();

        assertThat(h.sock().sender().probesOut())
                .as("no probes yet — just zero-window notice")
                .isZero();

        // Step 3:栈再 push 新数据 —— 由于对端 wnd=0,应进入 probe0 路径
        byte[] second = "B".getBytes(StandardCharsets.UTF_8);
        h.send(second);
        harness.channel().runPendingTasks();

        // 栈仍受对端零窗限制,不能立即发出 new payload
        assertThat(h.sock().packetsOut())
                .as("zero-window blocks new xmit")
                .isZero();

        // Step 4:推进定时器到 probe0 基线之外,应触发探测
        harness.channel().advanceTimeBy(TcpProbeDefaults.PROBE0_BASE_MS + 20, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // Step 5:应有一个出站探测段(v2 走 xmitProbeSkb:0 字节负载,seq 在对端零窗外)
        Tcp4PacketBuf probe = harness.readOutboundTcp();
        assertThat(probe).as("zero-window probe should have been emitted").isNotNull();
        try {
            // 探测段为 ACK,不带 SYN/FIN/RST(对齐 Linux xmitProbeSkb)
            assertThat(probe.isAck()).isTrue();
            assertThat(probe.isRst()).isFalse();
            assertThat(probe.isSyn()).isFalse();
            // seq 位于对端零窗之外:probe 取 sndNxt - 1(1-byte past acked,见 Linux)
            assertThat(probe.tcpSeq()).isGreaterThanOrEqualTo(firstSeq + firstLen - 1);
        } finally {
            probe.release();
        }

        // probesOut 应递增
        assertThat(h.sock().sender().probesOut())
                .as("probesOut must increment after firing one probe")
                .isGreaterThanOrEqualTo(1);

        // Step 6:对端回 ACK 且 window > 0,probesOut 应被 tcpAckProbe 重置为 0
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, firstSeq + firstLen));
        harness.channel().runPendingTasks();

        assertThat(h.sock().sender().probesOut())
                .as("probesOut reset on new window open")
                .isZero();
    }

    // ---- helpers ----

    /**
     * 读第一个 TCP 出站段;若有多个,保留剩余供后续读取。
     * advanceTimeBy 可能触发多个 scheduled task,这里只关心第一个探测段。
     */
    private Tcp4PacketBuf drainFirstPacket() {
        Tcp4PacketBuf p = harness.readOutboundTcp();
        return p;
    }

    private int completeHandshake() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
        int isn = synAck.tcpSeq();
        synAck.release();

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();
        return isn;
    }

    /** 常量集中点,避免跨模块引用私有 TcpConstants。 */
    private static final class TcpProbeDefaults {
        /** 对齐 {@code TcpConstants.RTO_MIN_MS}(probe0 基线下限)。 */
        static final long PROBE0_BASE_MS = 200L;
    }
}
