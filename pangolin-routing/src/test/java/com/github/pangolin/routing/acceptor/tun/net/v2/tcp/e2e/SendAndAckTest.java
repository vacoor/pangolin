package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.CapturingInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:栈侧主动发数据 → 客户端 ACK → {@code snd_una} 推进。
 *
 * <p>覆盖 R2 抽 Sender 时最关键的路径:
 * <ol>
 *   <li>{@code sendmsg → tcp_queue_skb → pushPendingFrames → tcp_write_xmit}
 *       一次发出一个 MSS 粒度 skb;</li>
 *   <li>收到 ACK 后 {@code tcp_clean_rtx_queue} 从 RTX 队列里释放已 ACK 的段,
 *       {@code snd_una} 向前推进。</li>
 * </ol>
 */
class SendAndAckTest {

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
    @DisplayName("栈发一段 payload → 出站 PSH+ACK 段,seq 对齐 server ISN+1")
    void stackSendsSingleSegment() {
        byte[] payload = "world".getBytes(StandardCharsets.UTF_8);
        CapturingInitializer.CapturingHandler h = initializer.handler();
        h.send(payload);

        Tcp4PacketBuf out = harness.readOutboundTcp();
        assertThat(out).as("stack should have emitted a data segment").isNotNull();
        try {
            assertThat(out.isAck()).isTrue();
            assertThat(out.isRst()).isFalse();
            assertThat(out.isSyn()).isFalse();
            assertThat(out.tcpSrcPort()).isEqualTo(SERVER_PORT);
            assertThat(out.tcpDstPort()).isEqualTo(CLIENT_PORT);
            // 栈侧发出的第一个字节 seq = server ISN + 1(SYN 消耗 1)
            assertThat(out.tcpSeq()).isEqualTo(serverIsn + 1);
            assertThat(out.tcpPayloadLength()).isEqualTo(payload.length);

            byte[] actual = new byte[payload.length];
            out.tcpPayloadSlice().getBytes(0, actual);
            assertThat(actual).isEqualTo(payload);
        } finally {
            out.release();
        }

        // 此刻 snd_una 仍指向旧位(未 ACK):
        assertThat(h.sock().sndUna())
                .as("snd_una should not advance before ACK")
                .isEqualTo(serverIsn + 1);
    }

    @Test
    @DisplayName("客户端 ACK 后 snd_una 推进,RTX 队列清空")
    void sndUnaAdvancesOnAck() {
        byte[] payload = "world".getBytes(StandardCharsets.UTF_8);
        CapturingInitializer.CapturingHandler h = initializer.handler();
        h.send(payload);

        // 消费栈的 data 段(本测试不关心其字段,上面已覆盖)
        Tcp4PacketBuf out = harness.readOutboundTcp();
        assertThat(out).isNotNull();
        int serverSeq = out.tcpSeq();
        int serverPayloadLen = out.tcpPayloadLength();
        out.release();

        // 客户端 ACK 栈发出去的字节
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverSeq + serverPayloadLen));

        // snd_una 应推进到 ACK 号
        assertThat(h.sock().sndUna())
                .as("snd_una should advance to ack number")
                .isEqualTo(serverSeq + serverPayloadLen);

        // RTX 队列不应再持有未 ACK 段(packetsOut = 0)
        assertThat(h.sock().packetsOut())
                .as("packets_out should be 0 after all bytes acked")
                .isZero();
    }

    // ---- helpers ----

    private int completeHandshake() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
        int isn = synAck.tcpSeq();
        synAck.release();

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));

        return isn;
    }
}
