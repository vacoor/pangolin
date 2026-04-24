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

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:SO_KEEPALIVE 开启后的空闲探测流程 —— 对齐 Linux
 * {@code tcp_keepalive_timer}(net/ipv4/tcp_timer.c)。
 *
 * <p>覆盖 R7.1 迁到 Sender 的 keepalive 家族字段(keepaliveEnabled /
 * keepaliveTimeMs / keepaliveIntvlMs / keepaliveProbes)以及 {@link
 * Sender#armKeepalive} / {@link Sender#keepaliveTimer}。
 *
 * <p>场景(短超时便于 advanceTimeBy 驱动):
 * <ol>
 *   <li>3WHS 完成;</li>
 *   <li>通过 Sender API 打开 keepalive 并设短 idle/intvl;</li>
 *   <li>armKeepalive 安排定时器;</li>
 *   <li>advanceTimeBy 到 keepaliveTime 之后 → timer fires → writeWakeup
 *       触发一次探测出站段;</li>
 *   <li>probesOut 增长;armKeepalive 被续期(间隔 = keepaliveIntvlMs)。</li>
 * </ol>
 */
class KeepaliveTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    // 注意:Sender.keepaliveElapsedMs 走 {@code TcpClock.tcp_jiffies32()}(真实时钟),
    // 而 EmbeddedChannel.advanceTimeBy 只动调度器内部时钟。因此 idle 必须设足够小
    // (小于从握手到测试探测之间的真实壁钟 delta),让 elapsed ≥ idleMs。
    private static final long KEEPALIVE_IDLE_MS = 1L;
    private static final long KEEPALIVE_INTVL_MS = 50L;
    private static final int KEEPALIVE_PROBES = 3;

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
    @DisplayName("开 keepalive + 空闲超 idleMs → 出站一个 keepalive 探测段,probesOut 增 1")
    void idleTriggersKeepaliveProbe() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // 配置短超时 keepalive
        sender.keepaliveTimeMs(KEEPALIVE_IDLE_MS);
        sender.keepaliveIntvlMs(KEEPALIVE_INTVL_MS);
        sender.keepaliveProbes(KEEPALIVE_PROBES);
        sender.keepaliveEnabled(true);
        sender.armKeepalive(KEEPALIVE_IDLE_MS);

        assertThat(sender.probesOut()).as("no probe yet").isZero();

        // 等少量真实时间,让 TcpClock.tcp_jiffies32 (wall clock) 跨过 1ms,确保
        // keepaliveElapsedMs >= idleMs,随后推进调度器内部时钟触发定时器。
        try { Thread.sleep(5L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        harness.channel().advanceTimeBy(KEEPALIVE_IDLE_MS + 20, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        // 应出一个 keepalive 探测段(ACK 段,无 payload 或 1 字节)
        Tcp4PacketBuf probe = harness.readOutboundTcp();
        assertThat(probe).as("keepalive timer must emit a probe").isNotNull();
        try {
            assertThat(probe.isAck()).isTrue();
            assertThat(probe.isSyn()).isFalse();
            assertThat(probe.isRst()).isFalse();
            assertThat(probe.tcpSrcPort()).isEqualTo(SERVER_PORT);
            assertThat(probe.tcpDstPort()).isEqualTo(CLIENT_PORT);
        } finally {
            probe.release();
        }

        // probesOut 应递增(writeWakeup 返回 <= 0 时加 1)
        assertThat(sender.probesOut())
                .as("probesOut must increment after keepalive probe")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("keepaliveEnabled=false → timer fire 空转,不出包")
    void disabledKeepaliveDoesNotProbe() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        sender.keepaliveTimeMs(KEEPALIVE_IDLE_MS);
        sender.keepaliveEnabled(false);  // 显式禁用
        // armKeepalive 在 disabled 下应该 early-return,定时器都不武装
        sender.armKeepalive(KEEPALIVE_IDLE_MS);

        harness.channel().advanceTimeBy(KEEPALIVE_IDLE_MS + 5, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        assertThat(harness.outboundSize())
                .as("disabled keepalive must not emit probes")
                .isZero();
        assertThat(sender.probesOut()).as("probesOut stays 0").isZero();
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
