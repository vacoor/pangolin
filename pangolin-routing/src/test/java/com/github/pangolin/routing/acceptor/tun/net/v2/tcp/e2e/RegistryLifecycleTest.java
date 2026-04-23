package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimewaitSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:sock / timewait 注册表生命周期可观测性 —— 对齐 Linux
 * {@code establishedRegistry} / {@code timewaitRegistry} 的 register / unregister
 * 动作。为后续 R4.2 把 registries 从 {@code TcpMultiplexer} 抽到 {@code TcpStack}
 * 提供行为回归网。
 *
 * <p>观测手段:
 * <ul>
 *   <li><b>行为探针</b>:对同一 4-tuple 发段,根据栈响应(数据交付 / RST / TW ACK 重放)
 *       推断 registry 命中的是哪条槽位;</li>
 *   <li><b>结构探针</b>:通过反射读 {@code establishedRegistry} / {@code timewaitRegistry}
 *       的 size,直接断言注册表形态。R4.2 之后这些字段可能搬到 {@code TcpStack},
 *       反射路径需同步更新 —— 正是本测试要"钉住"的形态。</li>
 * </ul>
 */
class RegistryLifecycleTest {

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
    // A. establishedRegistry 生命周期
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("握手完成 → establishedRegistry 含一条;tcpDone 后摘除")
    void tcpDoneRemovesSockFromEstablishedRegistry() {
        int serverIsn = completeHandshakeAndDrain();
        TcpMultiplexer mx = initializer.handler().multiplexer();

        assertThat(established(mx)).as("handshake should populate established registry").hasSize(1);
        assertThat(timewait(mx)).as("no TW bucket yet").isEmpty();

        TcpSock sock = initializer.handler().sock();
        mx.tcpDone(sock);
        harness.channel().runPendingTasks();

        assertThat(established(mx))
                .as("tcpDone should unregister from established registry")
                .isEmpty();
        assertThat(initializer.handler().destroyed())
                .as("onSocketDestroyed must fire")
                .isTrue();

        // 行为探针:同 4-tuple 再发数据段 → 落到 LISTEN 状态机 ACK 分支 → RST
        ByteBuf payload = Unpooled.wrappedBuffer("late-data".getBytes(StandardCharsets.UTF_8));
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1, payload));

        Tcp4PacketBuf rsp = harness.readOutboundTcp();
        assertThat(rsp).as("post-tcpDone data should elicit RST, not delivery").isNotNull();
        try {
            assertThat(rsp.isRst()).isTrue();
        } finally {
            rsp.release();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // B. timewaitRegistry 生命周期
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("timeWait 迁移:established 摘除 + timewait 新增(同 4-tuple)")
    void timeWaitMigratesBetweenRegistries() {
        completeHandshakeAndDrain();
        TcpMultiplexer mx = initializer.handler().multiplexer();
        TcpSock sock = initializer.handler().sock();

        mx.timeWait(sock, TcpConnectionState.TIME_WAIT, /*timeoutMs=*/ 60_000L);
        harness.channel().runPendingTasks();

        assertThat(established(mx))
                .as("TW staging must evict from established registry")
                .isEmpty();
        assertThat(timewait(mx))
                .as("TW staging must insert into timewait registry")
                .hasSize(1);
    }

    @Test
    @DisplayName("inet_twsk_kill → timewait 摘除")
    void inetTwskKillRemovesBucket() {
        completeHandshakeAndDrain();
        TcpMultiplexer mx = initializer.handler().multiplexer();
        TcpSock sock = initializer.handler().sock();

        mx.timeWait(sock, TcpConnectionState.TIME_WAIT, /*timeoutMs=*/ 60_000L);
        harness.channel().runPendingTasks();

        TcpTimewaitSock tw = timewait(mx).values().iterator().next();
        mx.inet_twsk_kill(tw);

        assertThat(timewait(mx))
                .as("inet_twsk_kill must remove bucket from registry")
                .isEmpty();
    }

    @Test
    @DisplayName("TW 2MSL 定时器到期 → bucket 自动摘除")
    void twBucketTimerExpiryRemovesBucket() {
        completeHandshakeAndDrain();
        TcpMultiplexer mx = initializer.handler().multiplexer();
        TcpSock sock = initializer.handler().sock();

        // 用短超时 staging,通过 advanceTimeBy 推进定时器
        mx.timeWait(sock, TcpConnectionState.TIME_WAIT, /*timeoutMs=*/ 50L);
        harness.channel().runPendingTasks();
        assertThat(timewait(mx)).hasSize(1);

        harness.channel().advanceTimeBy(60, TimeUnit.MILLISECONDS);
        harness.channel().runScheduledPendingTasks();
        harness.channel().runPendingTasks();

        assertThat(timewait(mx))
                .as("2MSL timer firing must invoke inet_twsk_kill")
                .isEmpty();
    }

    @Test
    @DisplayName("多 TW bucket 按 4-tuple 独立隔离(kill 一个不影响另一个)")
    void twBucketIsolatedByFourTuple() {
        int serverIsnA = completeHandshakeAndDrain(CLIENT_PORT);
        TcpMultiplexer mx = initializer.handler().multiplexer();
        TcpSock sockA = initializer.handler().sock();

        // 第二条连接:不同 client port,保证 4-tuple 不同
        int serverIsnB = completeHandshakeAndDrain(CLIENT_PORT + 1);
        TcpSock sockB = initializer.handlers().get(1).sock();

        mx.timeWait(sockA, TcpConnectionState.TIME_WAIT, 60_000L);
        mx.timeWait(sockB, TcpConnectionState.TIME_WAIT, 60_000L);
        harness.channel().runPendingTasks();

        assertThat(timewait(mx)).as("two independent TW buckets").hasSize(2);

        // kill 其中一个
        TcpTimewaitSock twA = timewait(mx).get(sockA.fourTuple());
        assertThat(twA).isNotNull();
        mx.inet_twsk_kill(twA);

        assertThat(timewait(mx))
                .as("killing bucket A must not affect bucket B")
                .hasSize(1);
        assertThat(timewait(mx).keySet())
                .as("surviving bucket must be for sockB's 4-tuple")
                .containsExactly(sockB.fourTuple());
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    private int completeHandshakeAndDrain() {
        return completeHandshakeAndDrain(CLIENT_PORT);
    }

    private int completeHandshakeAndDrain(int clientPort) {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, clientPort, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
        int serverIsn = synAck.tcpSeq();
        synAck.release();

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, clientPort, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1));
        harness.channel().runPendingTasks();
        return serverIsn;
    }

    /**
     * 反射读 {@code establishedRegistry}。R4.2 之后若 registries 搬家,这里
     * 需要同步改 —— 这恰是本测试要"钉住"的结构观测点。
     */
    @SuppressWarnings("unchecked")
    private static Map<Object, TcpSock> established(TcpMultiplexer mx) {
        try {
            Field f = TcpMultiplexer.class.getDeclaredField("establishedRegistry");
            f.setAccessible(true);
            return (Map<Object, TcpSock>) f.get(mx);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reflect establishedRegistry", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, TcpTimewaitSock> timewait(TcpMultiplexer mx) {
        try {
            Field f = TcpMultiplexer.class.getDeclaredField("timewaitRegistry");
            f.setAccessible(true);
            return (Map<Object, TcpTimewaitSock>) f.get(mx);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reflect timewaitRegistry", e);
        }
    }
}
