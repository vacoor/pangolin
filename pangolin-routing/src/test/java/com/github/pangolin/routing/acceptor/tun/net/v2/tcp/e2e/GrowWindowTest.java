package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Receiver;
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
 * E2E:接收缓冲 slow-start(tcp_grow_window, RFC 1323 + Linux tcp_input.c)。
 *
 * <p>场景:
 * <ol>
 *   <li>3WHS 完成,初始 {@code rcvSsthresh = rcvWnd = 65535},
 *       {@code windowClamp = TCP_DEFAULT_RCV_BUF = 87380};</li>
 *   <li>客户端连续发 2 个 MSS 大小的顺序段;</li>
 *   <li>每段到达后,{@code Receiver.tcpGrowWindow} 把 {@code rcvSsthresh}
 *       朝 {@code min(windowClamp, freeSpace)} 推进 {@code 2*advmss};</li>
 *   <li>验证 {@code rcvSsthresh} 严格单调增长,并受 {@code windowClamp} /
 *       {@code freeSpace} 上限约束。</li>
 * </ol>
 *
 * <p>对应 Linux:{@code tcp_event_data_recv → tcp_grow_window}(net/ipv4/tcp_input.c)。
 */
class GrowWindowTest {

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
    @DisplayName("连续接收 MSS 顺序段 → rcvSsthresh 单调增长 2*advmss / 段(上限 windowClamp)")
    void rcvSsthreshGrowsWithInOrderData() {
        Receiver receiver = initializer.handler().sock().receiver();

        int initial = receiver.rcvSsthresh();
        int windowClamp = receiver.windowClamp();
        // 对齐 Linux tcp_init_buffer_space:rcvSsthresh 起始 = min(rcvWnd, 4*advmss)
        // = min(87380, 4*1460) = 5840
        assertThat(initial)
                .as("rcvSsthresh 起始 = min(rcvWnd, 4*advmss)")
                .isEqualTo(4 * MSS);
        assertThat(windowClamp)
                .as("windowClamp 起始 = TCP_DEFAULT_RCV_BUF")
                .isEqualTo(87380);

        // 客户端发 seg0:MSS 字节
        int seq = CLIENT_ISN + 1;
        ByteBuf p0 = Unpooled.buffer(MSS).writerIndex(MSS);
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                seq, serverIsn + 1, p0));
        harness.channel().runPendingTasks();

        int afterSeg0 = receiver.rcvSsthresh();
        assertThat(afterSeg0)
                .as("seg0 payload ≥ 128 → grow 一次,rcvSsthresh 增加")
                .isGreaterThan(initial);

        // 客户端发 seg1
        seq += MSS;
        ByteBuf p1 = Unpooled.buffer(MSS).writerIndex(MSS);
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                seq, serverIsn + 1, p1));
        harness.channel().runPendingTasks();

        int afterSeg1 = receiver.rcvSsthresh();
        assertThat(afterSeg1)
                .as("seg1 继续推进 rcvSsthresh,单调递增")
                .isGreaterThan(afterSeg0);

        // 上限不超过 windowClamp
        assertThat(afterSeg1).isLessThanOrEqualTo(windowClamp);

        // drain any outbound ACKs so tearDown 不报残留
        Tcp4PacketBuf p;
        while ((p = harness.readOutboundTcp()) != null) p.release();
    }

    @Test
    @DisplayName("< 128 字节小段不触发 grow(Linux 门槛)")
    void tinySegmentDoesNotGrow() {
        Receiver receiver = initializer.handler().sock().receiver();
        int initial = receiver.rcvSsthresh();

        int seq = CLIENT_ISN + 1;
        byte[] tiny = new byte[50];           // < 128 门槛
        ByteBuf p = Unpooled.wrappedBuffer(tiny);
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                seq, serverIsn + 1, p));
        harness.channel().runPendingTasks();

        assertThat(receiver.rcvSsthresh())
                .as("< 128 字节段跳过 grow 分支,rcvSsthresh 不变")
                .isEqualTo(initial);

        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) out.release();
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
