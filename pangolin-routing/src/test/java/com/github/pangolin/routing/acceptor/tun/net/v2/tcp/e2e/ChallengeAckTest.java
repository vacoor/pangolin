package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:ESTABLISHED 连接收到越窗段(OOW data / OOW pure ACK)触发 challenge ACK。
 *
 * <p>覆盖 R4.2b-4f 迁到 {@link
 * com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Receiver#outOfWindow}
 * 的分支 —— 段不在 {@code [rcv_nxt, rcv_nxt + rwnd)} 窗口内时:
 * <ul>
 *   <li>进入 quickack 模式(MAX_QUICKACKS 次);</li>
 *   <li>置 ACK_SCHED,由 {@code ackSndCheck} 立即出 ACK;</li>
 *   <li>MIB 累加 OUTOFWINDOWICMPS + drop reason。</li>
 * </ul>
 *
 * <p>场景:
 * <ol>
 *   <li>3WHS 完成,{@code rcv_nxt = CLIENT_ISN + 1}, rwnd ≈ 65535;</li>
 *   <li>客户端发一段数据,seq 远超 rcv_nxt + rwnd(明显越窗),payload 不应被交付;</li>
 *   <li>栈立即出 ACK,{@code ack = rcv_nxt}(未推进);</li>
 *   <li>handler 没有收到 inbound data(丢弃)。</li>
 * </ol>
 */
class ChallengeAckTest {

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
    @DisplayName("OOW 数据段(seq 越窗)→ 丢数据 + 回 challenge ACK,rcv_nxt 不推进")
    void oowDataTriggersChallengeAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        int priorRcvNxt = h.sock().rcvNxt();
        assertThat(priorRcvNxt).isEqualTo(CLIENT_ISN + 1);

        // 构造越窗数据段:seq = rcv_nxt + 10_000_000(远超窗口上限)
        int oowSeq = CLIENT_ISN + 1 + 10_000_000;
        ByteBuf payload = Unpooled.wrappedBuffer("stale".getBytes(StandardCharsets.UTF_8));
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                oowSeq, serverIsn + 1, payload));
        harness.channel().runPendingTasks();

        // handler 不应收到数据
        assertThat(h.inboundPayloads())
                .as("OOW payload must be discarded, not delivered")
                .isEmpty();

        // rcv_nxt 不应推进
        assertThat(h.sock().rcvNxt())
                .as("rcv_nxt must stay at prior value when OOW")
                .isEqualTo(priorRcvNxt);

        // 栈应立即回 challenge ACK(quickack 模式 + ACK_SCHED → ackSndCheck 立即出)
        Tcp4PacketBuf ack = harness.readOutboundTcp();
        assertThat(ack).as("challenge ACK should be emitted").isNotNull();
        try {
            assertThat(ack.isAck()).isTrue();
            assertThat(ack.isRst()).isFalse();
            assertThat(ack.isSyn()).isFalse();
            // ack_num 应等于 rcv_nxt,不因越窗段推进
            assertThat(ack.tcpAckNum())
                    .as("challenge ACK points to unchanged rcv_nxt")
                    .isEqualTo(priorRcvNxt);
            assertThat(ack.tcpSrcPort()).isEqualTo(SERVER_PORT);
            assertThat(ack.tcpDstPort()).isEqualTo(CLIENT_PORT);
        } finally {
            ack.release();
        }
    }

    @Test
    @DisplayName("OLD_DATA(seq 落在 rcv_nxt 之前)→ 丢数据 + 立即 ACK")
    void oldDataTriggersImmediateAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        int priorRcvNxt = h.sock().rcvNxt();

        // 先推进 rcv_nxt:发一段正常数据
        ByteBuf payload1 = Unpooled.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8));
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                priorRcvNxt, serverIsn + 1, payload1));
        harness.channel().advanceTimeBy(250, java.util.concurrent.TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        int advancedRcvNxt = h.sock().rcvNxt();
        assertThat(advancedRcvNxt)
                .as("rcv_nxt should advance by 3 after valid data")
                .isEqualTo(priorRcvNxt + 3);

        // 排干 ACK 对 priorRcvNxt+3 的回包
        Tcp4PacketBuf firstAck;
        while ((firstAck = harness.readOutboundTcp()) != null) {
            firstAck.release();
        }

        // 现在重放老的 seq = priorRcvNxt(payload 已被处理过)
        ByteBuf replay = Unpooled.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8));
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                priorRcvNxt, serverIsn + 1, replay));
        harness.channel().runPendingTasks();

        // OLD_DATA 分支:段 end_seq ≤ rcv_nxt 时丢弃,quickack + ACK_SCHED → 立即 ACK
        Tcp4PacketBuf ack = harness.readOutboundTcp();
        assertThat(ack).as("OLD_DATA must still produce an ACK (quickack)").isNotNull();
        try {
            assertThat(ack.isAck()).isTrue();
            assertThat(ack.tcpAckNum())
                    .as("ACK still points to current rcv_nxt")
                    .isEqualTo(advancedRcvNxt);
        } finally {
            ack.release();
        }

        // handler 不应重复收到 payload
        assertThat(h.inboundPayloads())
                .as("replay should not be delivered twice")
                .hasSize(1);
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
