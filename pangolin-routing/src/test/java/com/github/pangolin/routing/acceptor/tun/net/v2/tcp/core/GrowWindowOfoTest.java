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
 * E2E:对齐 Linux {@code tcp_data_queue_ofo} 末尾的 {@code tcp_grow_window} 调用。
 * OFO 段成功入队后(且未触发 prune)也应推进 rcv_ssthresh,而不是只在 in-order 路径推进。
 */
class GrowWindowOfoTest {

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
    @DisplayName("OFO 段入队 → rcvSsthresh 也应推进(对齐 tcp_data_queue_ofo 末尾 grow 调用)")
    void ofoSegmentGrowsRcvSsthresh() {
        Receiver receiver = initializer.handler().sock().receiver();
        int initial = receiver.rcvSsthresh();
        assertThat(initial).as("init = 4*MSS").isEqualTo(4 * MSS);

        // 跳过 rcv_nxt(seq=1001)发一个 OFO 段(seq=10001)
        ByteBuf p = Unpooled.buffer(MSS).writerIndex(MSS);
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 10001, serverIsn + 1, p));
        harness.channel().runPendingTasks();

        // drain ack 噪声
        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) out.release();

        assertThat(receiver.rcvSsthresh())
                .as("OFO 路径也应触发 grow → rcvSsthresh 增长")
                .isGreaterThan(initial);
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
