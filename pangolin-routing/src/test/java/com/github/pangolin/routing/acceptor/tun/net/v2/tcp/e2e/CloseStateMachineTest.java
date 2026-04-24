package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpStack;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimewaitSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:TCP 连接关闭状态机的完整 4-way 路径 —— 对齐 Linux RFC 9293 §3.5。
 *
 * <p>覆盖 3 条 close 主路径:
 * <ul>
 *   <li><b>被动关闭</b>:对端先发 FIN → CLOSE_WAIT → 本端 shutdown → LAST_ACK
 *       → 对端 ACK → CLOSED(无 TW bucket)</li>
 *   <li><b>主动关闭</b>:本端先 shutdown → FIN_WAIT_1 → 对端 ACK → FIN_WAIT_2
 *       → 对端 FIN → TIME_WAIT(TW bucket 创建,原 sock 销毁)</li>
 *   <li><b>同时关闭</b>:FIN_WAIT_1 收到对端 FIN → CLOSING → 对端 ACK → TIME_WAIT</li>
 * </ul>
 *
 * <p>这些路径跨 R4.2b-4f {@code Receiver.finIncoming} 的全状态 switch +
 * {@code Ipv4SegmentDispatcher.rcvStateProcess} 的 FIN_WAIT_1 / CLOSING /
 * LAST_ACK 分支 + R4.2b-2 {@code TcpStack.timeWait} / {@code inet_csk_destroy_sock},
 * 是 FSM 最复杂的区域之一。
 */
class CloseStateMachineTest {

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

    // ════════════════════════════════════════════════════════════════════════
    // A. 被动关闭:对端先 FIN
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("被动关闭:FIN → CLOSE_WAIT → shutdown → LAST_ACK → 对端 ACK → CLOSED,无 TW")
    void passiveCloseToLastAck() {
        CapturingInitializer.CapturingHandler h = initializer.handler();

        // Step 1: 对端 FIN → 栈 CLOSE_WAIT + ACK
        harness.sendInbound(PacketFactory.fin(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));
        harness.channel().advanceTimeBy(250, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.CLOSE_WAIT);
        assertThat(h.peerFinCount()).isEqualTo(1);
        drainAll();  // 吃掉 ACK

        // Step 2: 栈主动 shutdown(SEND_SHUTDOWN)→ 迁 LAST_ACK + 发 FIN
        h.sock().sender().shutdown(TcpConstants.SEND_SHUTDOWN);
        harness.channel().runPendingTasks();

        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.LAST_ACK);

        Tcp4PacketBuf fin = harness.readOutboundTcp();
        assertThat(fin).as("LAST_ACK 迁移应发 FIN").isNotNull();
        int finSeq;
        try {
            assertThat(fin.isFin()).isTrue();
            assertThat(fin.isAck()).isTrue();
            finSeq = fin.tcpSeq();
        } finally {
            fin.release();
        }

        // Step 3: 对端 ACK 我们的 FIN → sock 销毁
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 2, finSeq + 1));  // client seq +1 for its FIN, ack finSeq+1
        harness.channel().runPendingTasks();

        assertThat(h.destroyed())
                .as("LAST_ACK + 收我方 FIN 的 ACK → sock 销毁")
                .isTrue();

        // 无 TIME_WAIT bucket(passive close 路径)
        assertThat(timewait(h.stack()))
                .as("passive close 不经过 TW bucket")
                .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // B. 主动关闭:本端先 shutdown
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("主动关闭:shutdown → FIN_WAIT_1 → ACK → sock 下沉为 TW(sub=FIN_WAIT_2)→ 对端 FIN → TW sub=TIME_WAIT")
    void activeCloseToTimeWait() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpStack stack = h.stack();

        // Step 1: 本端 shutdown → FIN_WAIT_1 + 发 FIN
        h.sock().sender().shutdown(TcpConstants.SEND_SHUTDOWN);
        harness.channel().runPendingTasks();
        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.FIN_WAIT_1);

        Tcp4PacketBuf fin = harness.readOutboundTcp();
        int finSeq = fin.tcpSeq();
        fin.release();

        // Step 2: 对端 ACK 我们的 FIN
        // v2 的 scheduleFinWait2Timeout 策略:若 tcpFinTimeMs ≤ TIME_WAIT_MS,立即
        // 把重量级 sock 下沉为 TW bucket(sub-state=FIN_WAIT_2),原 sock 销毁。
        // 这是 Linux tcp_time_wait(sk, TCP_FIN_WAIT2, tmo) 的 v2 等价路径,
        // 比"先留在 FIN_WAIT_2 再 TW"更节省内存,行为对端线上一致。
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, finSeq + 1));
        harness.channel().runPendingTasks();

        assertThat(h.destroyed())
                .as("sock 下沉为 TW bucket 后即销毁")
                .isTrue();
        assertThat(timewait(stack))
                .as("TW bucket 应持有 FIN_WAIT_2 sub-state")
                .hasSize(1);
        TcpTimewaitSock tw = timewait(stack).values().iterator().next();
        assertThat(tw.tw_substate)
                .as("TW sub-state 为 FIN_WAIT_2(等对端 FIN)")
                .isEqualTo(TcpConnectionState.FIN_WAIT_2);

        // Step 3: 对端 FIN → TW handleIncoming 把 sub-state 迁到 TIME_WAIT
        harness.sendInbound(PacketFactory.fin(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, finSeq + 1));
        harness.channel().runPendingTasks();

        assertThat(tw.tw_substate)
                .as("FIN_WAIT_2 收到对端 FIN → sub-state=TIME_WAIT")
                .isEqualTo(TcpConnectionState.TIME_WAIT);
        // tw_rcv_nxt 应推进 +1(FIN 占一个 seq)
        assertThat(tw.tw_rcv_nxt)
                .as("FIN 消耗一个 seq,rcv_nxt +1")
                .isEqualTo(CLIENT_ISN + 2);

        // 出站应是 ACK 重放(TCP_TW_ACK)
        drainAll();
    }

    // ════════════════════════════════════════════════════════════════════════
    // C. 同时关闭:FIN_WAIT_1 中收到对端 FIN(无 ACK)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同时关闭:FIN_WAIT_1 + 对端 FIN → CLOSING → 对端 ACK → TW bucket")
    void simultaneousCloseToTimeWait() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpStack stack = h.stack();

        // 本端 shutdown → FIN_WAIT_1
        h.sock().sender().shutdown(TcpConstants.SEND_SHUTDOWN);
        harness.channel().runPendingTasks();
        Tcp4PacketBuf fin = harness.readOutboundTcp();
        int finSeq = fin.tcpSeq();
        fin.release();
        assertThat(h.sock().state()).isEqualTo(TcpConnectionState.FIN_WAIT_1);

        // 对端 FIN(不 ACK 我方 FIN)→ 同时关闭 → CLOSING
        harness.sendInbound(PacketFactory.fin(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));  // ack 不覆盖我方 FIN(仍是 serverIsn+1)
        harness.channel().runPendingTasks();

        assertThat(h.sock().state())
                .as("FIN_WAIT_1 + 对端 FIN + 不 ACK 我方 → CLOSING (simultaneous close)")
                .isEqualTo(TcpConnectionState.CLOSING);
        drainAll();  // ACK for peer FIN

        // 对端 ACK 我方 FIN → TIME_WAIT
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 2, finSeq + 1));
        harness.channel().runPendingTasks();

        assertThat(h.destroyed()).isTrue();
        assertThat(timewait(stack))
                .as("同时关闭末尾也进 TW bucket")
                .hasSize(1);
    }

    // ══════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static Map<Object, TcpTimewaitSock> timewait(TcpStack stack) {
        try {
            Field f = TcpStack.class.getDeclaredField("timewaitRegistry");
            f.setAccessible(true);
            return (Map<Object, TcpTimewaitSock>) f.get(stack);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void drainAll() {
        Tcp4PacketBuf p;
        while ((p = harness.readOutboundTcp()) != null) p.release();
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
}
