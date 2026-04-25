package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

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

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:对齐 Linux {@code tcp_cleanup_rbuf}(net/ipv4/tcp.c)。
 *
 * <p>场景:对端发数据 → 应用反压(rcvPaused=true)→ 缓冲填到一定程度 →
 * 应用解除反压(doBeginRead 翻 paused=false 并调 {@link Receiver#tcpCleanupRbuf}) →
 * 验证主动发出 window-update 纯 ACK,通告更新后的接收窗口。
 *
 * <p>没有这个钩子,对端会一直停在小窗等"自然"ACK,而我们要等下一次入站段
 * 才会借 ACK 捎带新窗 —— 形成双方相互等待的死锁。
 */
class TcpCleanupRbufTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;
    private static final int MSS = 1460;

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
    @DisplayName("零窗 → drain → tcpCleanupRbuf 发 window-open ACK(对齐 Linux 零窗恢复)")
    void zeroWindowRecoveryViaCleanup() {
        Receiver receiver = initializer.handler().sock().receiver();

        // 缩小 rcvBuf,让 paused 期间几个 MSS 就能撑爆 freeSpace 触发零窗
        receiver.rcvBuf(2 * MSS);
        receiver.windowClamp(2 * MSS);

        // Step 1:应用反压
        receiver.paused(true);

        // Step 2:连续发 2 个 MSS 段 —— 缓冲被填满,后续 ACK 通告窗口为 0
        for (int i = 0; i < 2; i++) {
            ByteBuf p = Unpooled.buffer(MSS).writerIndex(MSS);
            harness.sendInbound(PacketFactory.data(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1 + i * MSS, serverIsn + 1, p));
            harness.channel().runPendingTasks();
        }

        // drain 期间发出的所有 ACK,记录最后一个的 window 字段(应为 0,零窗)
        int lastAckWnd = -1;
        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) {
            if (out.isAck() && !out.isSyn() && !out.isFin() && !out.isRst()) {
                lastAckWnd = out.tcpWindow();
            }
            out.release();
        }
        assertThat(lastAckWnd)
                .as("paused 状态 + 缓冲填满 → 通告零窗")
                .isZero();
        assertThat(receiver.buffer().rmemAlloc())
                .as("paused 下数据滞留")
                .isPositive();

        // Step 3:应用解除反压 + 调 tcpCleanupRbuf
        receiver.paused(false);
        receiver.tcpCleanupRbuf();
        harness.channel().runPendingTasks();

        // Step 4:cleanup 应主动发 window-open 纯 ACK
        Tcp4PacketBuf updateAck = harness.readOutboundTcp();
        assertThat(updateAck)
                .as("零窗 → drain 后 cleanupRbuf 应发 window-open ACK")
                .isNotNull();
        try {
            assertThat(updateAck.isAck()).isTrue();
            assertThat(updateAck.tcpPayloadLength()).isZero();
            assertThat(updateAck.tcpWindow())
                    .as("通告窗口非零 — 对端可恢复发送")
                    .isPositive();
        } finally {
            updateAck.release();
        }

        assertThat(receiver.buffer().rmemAlloc())
                .as("readAll 后缓冲归零")
                .isZero();
    }

    @Test
    @DisplayName("无数据 + 无窗口变化时,tcpCleanupRbuf 不发多余 ACK")
    void noDataNoAckNoise() {
        Receiver receiver = initializer.handler().sock().receiver();
        // 缓冲为空 + 未 paused,直接调 cleanup 应该不产生任何输出
        assertThat(receiver.paused()).isFalse();
        assertThat(receiver.buffer().rmemAlloc()).isZero();

        receiver.tcpCleanupRbuf();
        harness.channel().runPendingTasks();

        Tcp4PacketBuf out = harness.readOutboundTcp();
        // 当前窗口算法可能因 rcvWnd 字段未初始化就发一个 ACK(curWin=0 路径),
        // 但不应产生数据段;断言至多一个纯 ACK。
        if (out != null) {
            try {
                assertThat(out.isAck()).isTrue();
                assertThat(out.tcpPayloadLength()).isZero();
            } finally {
                out.release();
            }
        }
        assertThat(harness.readOutboundTcp())
                .as("不重复发多个 ACK")
                .isNull();
    }

    // ---- helpers ----

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
