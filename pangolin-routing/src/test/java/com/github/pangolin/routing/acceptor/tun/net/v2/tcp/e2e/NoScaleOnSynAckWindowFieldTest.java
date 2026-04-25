package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
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
 * 对齐 RFC 7323 §2.2 / Linux {@code tcp_make_synack}:
 * SYN/SYN-ACK 自身的 {@code window} 字段 MUST NOT 被 wscale 缩放,
 * 只截到 16-bit 上限(65535)。
 *
 * <p>历史 bug:v2 {@code TcpHandshaker.sendSynAck} 把 {@code initialRcvWnd}
 * 错误地右移了 {@code config.windowScale()},导致默认 87380 字节的初始
 * 窗口在 SYN-ACK 上变成 682(= 87380 >> 7)。这让对端在 3WH 完成后第一波
 * 数据被严重抑制,要等下一个 ACK 才能借 wscale 看到真实 87296 字节。
 */
class NoScaleOnSynAckWindowFieldTest {

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

    @Test
    @DisplayName("客户端 SYN 带 WS=5 → SYN-ACK window 不被 rcvWscale 右移,值为 min(initialRcvWnd, 65535)")
    void synAckWindowFieldNotScaledWhenWsNegotiated() {
        // 客户端 SYN 带 MSS + WS=5
        byte[] synOpts = mssAndWsOptions(1460, 5);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN)
                .flags(PacketFactory.TCP_FLAG_SYN)
                .options(synOpts)
                .build());

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        try {
            assertThat(synAck.isSyn() && synAck.isAck()).as("SYN-ACK").isTrue();
            int wireWindow = synAck.tcpWindow();

            // 默认 initialRcvWnd = TCP_DEFAULT_RCV_BUF = 87380。
            // 修复前(buggy):87380 >> 7 = 682。
            // 修复后(对齐 Linux):min(87380, 65535) = 65535。
            assertThat(wireWindow)
                    .as("SYN-ACK window 应是字节实数(截 16-bit),不被 wscale 缩放")
                    .isEqualTo(65535);
            assertThat(wireWindow)
                    .as("严防回退到右移过的 buggy 值 682")
                    .isNotEqualTo(682);
        } finally {
            synAck.release();
        }
    }

    @Test
    @DisplayName("无 WS 协商 → SYN-ACK window 同样直接送字节实数(截 16-bit)")
    void synAckWindowFieldUnscaledWithoutWsNegotiation() {
        byte[] synOpts = mssOnly(1460);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN)
                .flags(PacketFactory.TCP_FLAG_SYN)
                .options(synOpts)
                .build());

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        try {
            int wireWindow = synAck.tcpWindow();
            // 无 WS 协商时,修复前 buggy 路径已经 = initialRcvWnd(>> 0 还是原值,但仍可能 > 65535);
            // 修复后路径加了 min(..., U16_MAX) 截断,这里值应为 65535。
            assertThat(wireWindow)
                    .as("无 WS 时 SYN-ACK window 同样按字节截 16-bit")
                    .isEqualTo(65535);
        } finally {
            synAck.release();
        }
    }

    // ---- helpers ----

    private static byte[] mssAndWsOptions(int mss, int wscale) {
        byte[] o = new byte[8];
        o[0] = 0x02; o[1] = 0x04;
        o[2] = (byte) (mss >>> 8); o[3] = (byte) mss;
        o[4] = 0x01;
        o[5] = 0x03; o[6] = 0x03; o[7] = (byte) wscale;
        return o;
    }

    private static byte[] mssOnly(int mss) {
        byte[] o = new byte[4];
        o[0] = 0x02; o[1] = 0x04;
        o[2] = (byte) (mss >>> 8); o[3] = (byte) mss;
        return o;
    }
}
