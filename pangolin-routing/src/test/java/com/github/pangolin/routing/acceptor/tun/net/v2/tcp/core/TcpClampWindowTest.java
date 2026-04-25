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
 * E2E:对齐 Linux {@code tcp_clamp_window}(net/ipv4/tcp_input.c)。
 *
 * <p>OFO 队列预算耗尽 → 触发 prune → {@code rcv_ssthresh} 写回到
 * {@code min(window_clamp, 2*advmss)},作为"压力记忆"防止后续短时反复
 * 打满 OFO 又开窗扩张。
 *
 * <p>测试用 {@code TcpReceiveBuffer.OFO_MAX_BYTES = 256KB}(常量) 量级的
 * 大量 OFO 段塞满队列,迫使 prune 触发,然后断言 {@code rcvSsthresh} 被
 * 收紧到 {@code 2*advmss = 2920}。
 */
class TcpClampWindowTest {

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
    @DisplayName("OFO prune 触发 → rcvSsthresh 被压到 min(windowClamp, 2*advmss)")
    void ofoPruneClampsRcvSsthresh() {
        Receiver receiver = initializer.handler().sock().receiver();

        // 初始 rcvSsthresh = min(rcvWnd, 4*advmss) = 5840。clamp 应把它压到
        // min(windowClamp, 2*advmss) = 2920。
        int initial = receiver.rcvSsthresh();
        assertThat(initial)
                .as("init 后 rcvSsthresh = 4*advmss")
                .isEqualTo(4 * MSS);

        // 把接收侧窗口预算放大到 2MB,让 200 个 MSS 段都落在 in-window 范围内,
        // 否则 TcpIncomingPreValidator 的 sequenceCheck 会把超出 rcvWup+rcvWnd
        // 的段直接 drop,根本到不了 offer()。
        final int bigBudget = 2 * 1024 * 1024;
        receiver.rcvBuf(bigBudget);
        receiver.windowClamp(bigBudget);
        receiver.rcvWnd(bigBudget);

        // 大量灌 OFO 段:跳过 rcv_nxt(seq=1001)直接发 seq=10001 起的连续段,
        // 强制都进 OFO 队列。每个段 1460 字节,塞够 OFO_MAX_BYTES (256KB) 即可
        // 触发 prune。180 段 × 1460B ≈ 263KB,稳过门槛。
        for (int i = 0; i < 200; i++) {
            int seq = CLIENT_ISN + 10001 + i * MSS;  // 永远在 rcv_nxt 之后(OFO)
            ByteBuf p = Unpooled.buffer(MSS).writerIndex(MSS);
            harness.sendInbound(PacketFactory.data(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    seq, serverIsn + 1, p));
            harness.channel().runPendingTasks();
        }

        // drain outbound ACK 噪声
        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) out.release();

        int afterPrune = receiver.rcvSsthresh();
        assertThat(afterPrune)
                .as("OFO prune 后 rcvSsthresh 被压到 min(windowClamp, 2*advmss)")
                .isLessThanOrEqualTo(2 * MSS);
        assertThat(afterPrune)
                .as("rcvSsthresh 严格降到 clamp 水位")
                .isLessThan(initial);
    }

    @Test
    @DisplayName("无 prune 时 rcvSsthresh 不被无故 clamp")
    void noPruneNoClamp() {
        Receiver receiver = initializer.handler().sock().receiver();
        int initial = receiver.rcvSsthresh();

        // 只发一个顺序段,远不到 OFO 预算
        ByteBuf p = Unpooled.buffer(MSS).writerIndex(MSS);
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1, p));
        harness.channel().runPendingTasks();

        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) out.release();

        // grow 应该让它增长(不是 clamp 下降)
        assertThat(receiver.rcvSsthresh())
                .as("无 prune,正常 grow 路径不会调 clamp")
                .isGreaterThan(initial);
    }

    // ---- helpers ----

    /** 带 WS 协商的 3WHS,使 rcvWscale=7,可接受窗口扩到 ~11MB,
     *  足够让 200 个 MSS OFO 段都落在窗内、稳定触发 prune。 */
    private int completeHandshake() {
        byte[] synOpts = new byte[]{
                0x02, 0x04, 0x05, (byte) 0xB4,  // MSS=1460
                0x01,                               // NOP
                0x03, 0x03, 0x07                    // WS=7
        };
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN)
                .flags(PacketFactory.TCP_FLAG_SYN)
                .options(synOpts)
                .build());
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
