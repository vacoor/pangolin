package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SegmentDispatcher;
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
 * E2E:入站段分派路由 —— 对齐 Linux {@code tcp_v4_rcv} 在四元组未命中 / TIME_WAIT bucket
 * 命中情形下的分支行为。为后续 R4.2 将 {@code SegmentDispatcher} 拆分为 {@code TcpStack} +
 * {@code SegmentDispatcher} 提供行为回归网。
 *
 * <p>覆盖:
 * <ul>
 *   <li>未命中 + 非 RST → outbound RST(LISTEN 状态机 ACK/数据 分支);</li>
 *   <li>未命中 + inbound RST → 静默丢弃(RFC 5961 抗反射);</li>
 *   <li>TIME_WAIT bucket 命中 + 迟到 FIN → ACK 重放;</li>
 *   <li>TIME_WAIT bucket 命中 + RST at rcv_nxt → kill bucket;</li>
 *   <li>TIME_WAIT bucket 命中 + SYN with advanced seq → 重建新连接(TW_SYN 重用端口)。</li>
 * </ul>
 *
 * <p>TW bucket 通过 {@link SegmentDispatcher#timeWait} 程序化构造,不走真实 4-way close
 * —— 测试的是 <b>分派行为</b>,不是闭合路径。
 */
class DispatcherRoutingTest {

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

    // ════════════════════════════════════════════════════════════════════════
    // A. 未命中四元组 —— 回落到 LISTEN 状态机的 ACK / RST 分支
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("未命中 4-tuple + pure ACK → LISTEN 状态机回 RST")
    void unmatchedAckElicitsRst() {
        // 无前置握手。任意 ACK 落进来 → __inet_lookup_skb 回落到 listener.listenSock
        //  → rcvStateProcess(LISTEN) 遇 ACK → return -1 → send_reset。
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                /*seq*/ 5000, /*ack*/ 6000));

        Tcp4PacketBuf rsp = harness.readOutboundTcp();
        assertThat(rsp).as("unmatched ACK should produce a RST").isNotNull();
        try {
            assertThat(rsp.isRst()).isTrue();
            // v4SendReset 对 ACK 段:seq = ack_num(来包),flags = RST(不带 ACK)
            assertThat(rsp.tcpSeq()).isEqualTo(6000);
            assertThat(rsp.tcpSrcPort()).isEqualTo(SERVER_PORT);
            assertThat(rsp.tcpDstPort()).isEqualTo(CLIENT_PORT);
        } finally {
            rsp.release();
        }
    }

    @Test
    @DisplayName("未命中 4-tuple + inbound RST → 静默丢弃(RFC 5961 抗反射)")
    void unmatchedInboundRstSilentlyDropped() {
        harness.sendInbound(PacketFactory.rst(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                /*seq*/ 7777));

        // LISTEN 状态机 isRst → return 0(静默)。v4SendReset 也会 early-return for RST。
        assertThat(harness.outboundSize())
                .as("inbound RST must not elicit any reply")
                .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // B. TIME_WAIT bucket 命中分支(timewaitStateProcess)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TW bucket + 迟到 FIN → ACK 重放(对齐 TCP_TW_ACK)")
    void timewaitLateFinReplaysAck() {
        int serverIsn = completeHandshakeAndDrain();
        stageTimeWait(TcpConnectionState.TIME_WAIT);

        // 客户端迟到 FIN:seq=CLIENT_ISN+1,ack=serverIsn+1 —— 正好落在 tw_rcv_nxt
        harness.sendInbound(PacketFactory.fin(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));

        // timewaitSendAck:seq=tw_snd_nxt,ack=tw_rcv_nxt,flags=ACK
        Tcp4PacketBuf ack = harness.readOutboundTcp();
        assertThat(ack).as("late FIN to TW bucket should replay ACK").isNotNull();
        try {
            assertThat(ack.isAck()).isTrue();
            assertThat(ack.isRst()).isFalse();
            assertThat(ack.isSyn()).isFalse();
            // tw snapshot 建于握手完成时:tw_snd_nxt=serverIsn+1,tw_rcv_nxt=CLIENT_ISN+1
            assertThat(ack.tcpSeq()).isEqualTo(serverIsn + 1);
            assertThat(ack.tcpAckNum()).isEqualTo(CLIENT_ISN + 1);
        } finally {
            ack.release();
        }
    }

    @Test
    @DisplayName("TW bucket + RST at rcv_nxt → kill bucket(之后同 4-tuple 段回落 LISTEN)")
    void timewaitRstAtRcvNxtKillsBucket() {
        int serverIsn = completeHandshakeAndDrain();
        stageTimeWait(TcpConnectionState.TIME_WAIT);

        // (1) TW + RST at rcv_nxt → inet_twsk_kill(tw),无响应
        harness.sendInbound(PacketFactory.rst(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1));
        assertThat(harness.outboundSize())
                .as("RST at rcv_nxt must not elicit reply; just kills bucket")
                .isZero();

        // (2) bucket 已死 —— 同 4-tuple 再来段 ACK,查找会回落到 listener.listenSock,
        //     LISTEN 状态机对 ACK 回 RST。观察到 RST 即证明 TW bucket 已摘除。
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));

        Tcp4PacketBuf rsp = harness.readOutboundTcp();
        assertThat(rsp).as("after bucket kill, unmatched ACK should elicit RST").isNotNull();
        try {
            assertThat(rsp.isRst()).isTrue();
        } finally {
            rsp.release();
        }
    }

    @Test
    @DisplayName("TW bucket + SYN seq advanced → kill + 新握手(TW_SYN 重用端口)")
    void timewaitSynReusesPort() {
        completeHandshakeAndDrain();
        stageTimeWait(TcpConnectionState.TIME_WAIT);

        // 新 SYN,seq 明显超过 tw_rcv_nxt(CLIENT_ISN+1) —— 触发 TW_SYN,
        // bucket 被杀后重新 tcp_v4_rcv 派发到 LISTEN 走 conn_request。
        int newIsn = CLIENT_ISN + 1000;
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, newIsn));

        // CapturingInitializer 默认接纳 SYN → sendSynAck → 出站 SYN-ACK
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).as("advanced SYN must launch a fresh handshake").isNotNull();
        try {
            assertThat(synAck.isSyn()).isTrue();
            assertThat(synAck.isAck()).isTrue();
            assertThat(synAck.tcpAckNum())
                    .as("SYN-ACK must ack the new ISN+1, not stale tw_rcv_nxt")
                    .isEqualTo(newIsn + 1);
        } finally {
            synAck.release();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    /** 完成 3WHS 并排干出站 SYN-ACK,返回 server ISN。 */
    private int completeHandshakeAndDrain() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
        int serverIsn = synAck.tcpSeq();
        synAck.release();

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));
        return serverIsn;
    }

    /**
     * 程序化把当前 ESTABLISHED sock 迁入 TIME_WAIT bucket —— 绕过 4-way close 直接
     * 触发 {@code SegmentDispatcher.timeWait},把 sock 快照拷进 timewaitRegistry 并
     * 销毁原 TcpSock。测试只关心分派行为,staging 走最短路径即可。
     */
    private void stageTimeWait(TcpConnectionState substate) {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        assertThat(h).as("handshake must have completed before TW staging").isNotNull();
        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.TCP_ESTABLISHED);

        h.stack().timeWait(h.sock(), substate, /*timeoutMs=*/ 60_000L);
        harness.channel().runPendingTasks();

        assertThat(h.destroyed()).as("original sock should be destroyed after TW staging").isTrue();
    }
}
