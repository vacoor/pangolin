package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
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
 * E2E:栈以 {@link TcpSockInitializer#DENY} 启动(对齐 Linux "no listener → RST" 语义)。
 * 任何 SYN 都应该立即得到 RST,不进入 3WHS。
 */
class HandshakeDenyTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    private TcpStackHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TcpStackHarness(TcpSockInitializer.DENY);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("SYN → RST(栈无监听器,立发 RST)")
    void synProducesRst() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf rsp = harness.readOutboundTcp();
        assertThat(rsp).as("expected exactly one outbound packet").isNotNull();

        try {
            // RST,方向反转
            assertThat(rsp.isRst()).as("response should be RST").isTrue();
            assertThat(rsp.isSyn()).as("RST must not carry SYN").isFalse();
            assertThat(rsp.tcpSrcPort()).isEqualTo(SERVER_PORT);
            assertThat(rsp.tcpDstPort()).isEqualTo(CLIENT_PORT);
            // v2 栈对 "无 listener SYN" 的 RST 语义:seq=0, ack=ISN+1
            // 参考 Linux tcp_v4_send_reset:<SEQ=0><ACK=RCV.NXT><CTL=RST|ACK>
            assertThat(rsp.isAck()).as("RST must carry ACK for SYN case").isTrue();
            assertThat(rsp.tcpAckNum()).isEqualTo(CLIENT_ISN + 1);
        } finally {
            rsp.release();
        }

        assertThat(harness.outboundSize())
                .as("should only produce one RST, no extra packets")
                .isZero();
    }

    @Test
    @DisplayName("连续两个 SYN 各自收到一个 RST(每个都独立销毁 req)")
    void repeatedSynEachGetsRst() {
        for (int i = 0; i < 2; i++) {
            harness.sendInbound(PacketFactory.syn(
                    CLIENT_IP, CLIENT_PORT + i, SERVER_IP, SERVER_PORT, CLIENT_ISN + i));

            Tcp4PacketBuf rsp = harness.readOutboundTcp();
            assertThat(rsp).as("iteration " + i).isNotNull();
            try {
                assertThat(rsp.isRst()).isTrue();
                assertThat(rsp.tcpAckNum()).isEqualTo(CLIENT_ISN + i + 1);
            } finally {
                rsp.release();
            }
        }
    }
}
