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
 * E2E:RTO 指数退避(RFC 6298 §5.5)—— 对端长期不 ACK,RTO timer 连续触发,
 * 每次触发:
 * <ul>
 *   <li>{@link Sender#backoff()} 增 {@code backoffShift}(下一轮 RTO 翻倍);</li>
 *   <li>{@code TcpRetransmitter.retransmit} 把 RTX 队首段重新发出去;</li>
 *   <li>重新武装下一个 RTO timer。</li>
 * </ul>
 *
 * <p>覆盖 R2.3 迁到 Sender 的 {@code backoffShift} 字段 + R7.2 迁到 Sender 的
 * {@link Sender#rtoMs()} 实现,以及 {@code TcpRetransmitter.onTimeout} 完整路径。
 */
class RtoBackoffTest {

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
    @DisplayName("对端不 ACK → 经 TLP + RTO,backoffShift 累加,同一段反复重传")
    void rtoBackoffOnRepeatedTimeouts() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // 栈发一段数据,client 永不 ACK
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        // 原始发送
        Tcp4PacketBuf firstOut = harness.readOutboundTcp();
        assertThat(firstOut).isNotNull();
        int firstSeq = firstOut.tcpSeq();
        int firstLen = firstOut.tcpPayloadLength();
        assertThat(firstLen).isEqualTo(payload.length);
        firstOut.release();

        assertThat(sender.packetsOut()).as("1 segment in flight").isEqualTo(1);
        assertThat(sender.backoffShift()).as("no RTO fired yet").isZero();

        // 注意:发送路径先安排 RETRANSMIT(rtoMs),随后又 rearm 成 TLP_PROBE(~2·srtt,更早)。
        // 因此时序上 TLP 先触发 sendLossProbe(不增 backoff),再重装 RETRANSMIT,
        // 下一个 RTO 到期才走 onTimeout → backoff。需要累计 advanceTimeBy 覆盖 TLP + RTO。
        int retransCount = 0;
        int loops = 0;
        while (sender.backoffShift() == 0 && loops++ < 8) {
            long rto = sender.rtoMs();
            harness.channel().advanceTimeBy(rto + 100, TimeUnit.MILLISECONDS);
            harness.channel().runScheduledPendingTasks();
            harness.channel().runPendingTasks();

            Tcp4PacketBuf p;
            while ((p = harness.readOutboundTcp()) != null) {
                assertThat(p.tcpSeq())
                        .as("all re-emitted segments carry original seq")
                        .isEqualTo(firstSeq);
                p.release();
                retransCount++;
            }
        }

        assertThat(sender.backoffShift())
                .as("backoffShift must increment at least once after RTO fires")
                .isGreaterThanOrEqualTo(1);
        assertThat(retransCount)
                .as("at least one retransmit packet was emitted during TLP/RTO burst")
                .isGreaterThanOrEqualTo(1);

        // packetsOut 仍为 1(同一段反复重传,不是新段)
        assertThat(sender.packetsOut())
                .as("packets_out stays 1 across retransmits")
                .isEqualTo(1);

        // 再推进一个 RTO 窗口,应再次 backoff(或已触顶 6)
        int before = sender.backoffShift();
        harness.channel().advanceTimeBy(sender.rtoMs() + 100, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();
        Tcp4PacketBuf pp;
        while ((pp = harness.readOutboundTcp()) != null) pp.release();

        assertThat(sender.backoffShift())
                .as("backoffShift either increments again or caps at 6")
                .satisfiesAnyOf(
                        shift -> assertThat(shift).isGreaterThanOrEqualTo(before + 1),
                        shift -> assertThat(shift).isEqualTo(6));
    }

    @Test
    @DisplayName("RTO 后对端 ACK → backoff 归零,RTX 队列清空")
    void rtoBackoffResetsOnAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        byte[] payload = "world".getBytes(StandardCharsets.UTF_8);
        h.send(payload);
        Tcp4PacketBuf firstOut = harness.readOutboundTcp();
        int firstSeq = firstOut.tcpSeq();
        int firstLen = firstOut.tcpPayloadLength();
        firstOut.release();

        // 推进直到 backoff 递增(覆盖 TLP + 至少一次 RTO)
        int loops = 0;
        while (sender.backoffShift() == 0 && loops++ < 8) {
            harness.channel().advanceTimeBy(sender.rtoMs() + 100, TimeUnit.MILLISECONDS);
            harness.channel().runScheduledPendingTasks();
            harness.channel().runPendingTasks();
            Tcp4PacketBuf p;
            while ((p = harness.readOutboundTcp()) != null) p.release();
        }
        assertThat(sender.backoffShift())
                .as("backoffShift must increment after RTO fires")
                .isGreaterThanOrEqualTo(1);

        // 对端 ACK 全部
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, firstSeq + firstLen));
        harness.channel().runPendingTasks();

        // backoff 应归零(Sender.resetBackoff 由 TcpAck 调用),packetsOut 清空
        assertThat(sender.backoffShift())
                .as("backoffShift resets after new data ACKed")
                .isZero();
        assertThat(sender.packetsOut()).isZero();
    }

    // ---- helpers ----

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
}
