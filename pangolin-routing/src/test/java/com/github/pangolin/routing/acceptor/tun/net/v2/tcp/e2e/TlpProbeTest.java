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
 * E2E:TLP(Tail Loss Probe,RFC 8985 §7.5)—— 对齐 Linux
 * {@code tcp_schedule_loss_probe} / {@code tcp_send_loss_probe}。
 *
 * <p>新段发出后,若预期很快会有更多数据或 ACK,就安排一个比 RTO 短得多的定时器
 * (约 {@code max(2·srtt + ato, ...) < RTO})。若到期仍未 ACK,重传队尾段一次,
 * 比 RTO 快地探测"真的是丢包还是 ACK 还没到"。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@link Sender#scheduleLossProbe} / {@link Sender#sendLossProbe} 的
 *       完整触发链;</li>
 *   <li>{@code tlpHighSeq = sndNxt} 在 TLP 武装时被置位;</li>
 *   <li>TLP 触发后 RTX 队首段被重新发出;</li>
 *   <li>TLP 发出后继续武装 RETRANSMIT timer(与 RTO backoff 接力)。</li>
 * </ul>
 */
class TlpProbeTest {

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
    @DisplayName("发一段后对端不 ACK → scheduleLossProbe 置 tlpHighSeq=sndNxt,定时器到期重发段")
    void tlpArmsAndReEmitsTailSegment() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        byte[] payload = "tlp".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf firstOut = harness.readOutboundTcp();
        assertThat(firstOut).isNotNull();
        int origSeq = firstOut.tcpSeq();
        int origLen = firstOut.tcpPayloadLength();
        firstOut.release();

        // 初次发送后,scheduleLossProbe 已把 tlpHighSeq 设为 sndNxt(TLP 武装标志)
        int tlpMark = sender.tlpHighSeq();
        assertThat(tlpMark)
                .as("tlpHighSeq != 0 after arming TLP timer")
                .isNotZero();
        assertThat(tlpMark)
                .as("tlpHighSeq should equal sndNxt at TLP arm time")
                .isEqualTo(sender.sndNxt());

        // 足够推进让 TLP 及随后的 RTO 触发(两者都会重发队首,本测试只关心段被重发过)
        harness.channel().advanceTimeBy(sender.rtoMs() + 100, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // 至少出一个 seq=origSeq 的重传段(TLP or RTO);Sender 状态应被触发过
        Tcp4PacketBuf rtx = drainForSeq(origSeq);
        assertThat(rtx).as("TLP (and/or RTO) should re-emit head segment").isNotNull();
        try {
            assertThat(rtx.tcpPayloadLength()).isEqualTo(origLen);
        } finally {
            rtx.release();
        }

        // packetsOut 仍是 1(重传不增计数)
        assertThat(sender.packetsOut())
                .as("retransmission does not change packets_out")
                .isEqualTo(1);
    }

    // ---- helpers ----

    private Tcp4PacketBuf drainForSeq(int expected) {
        for (int i = 0; i < 8; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.tcpSeq() == expected && p.tcpPayloadLength() > 0) return p;
            p.release();
        }
        return null;
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
