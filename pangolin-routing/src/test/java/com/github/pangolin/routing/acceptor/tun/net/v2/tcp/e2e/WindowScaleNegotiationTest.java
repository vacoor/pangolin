package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
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
 * E2E:Window Scale 选项协商(RFC 7323 §2)—— 对齐 Linux
 * {@code tcp_parse_options} / {@code tcp_grow_window} 的 WS 处理。
 *
 * <p>场景:
 * <ul>
 *   <li>客户端 SYN 带 MSS + WS=5 选项;</li>
 *   <li>栈 SYN-ACK 带 WS={@code TcpConfig.windowScale()}(默认 7);</li>
 *   <li>完成 3WHS 后 sock.sndWscale=5(对端 WS,解释对端 window 值),
 *       sock.rcvWscale=7(本端 WS,缩放本端通告 window);</li>
 *   <li>后续 ACK 中的 {@code window} 字段在本端解释为 {@code window << sndWscale};
 *       SYN-ACK 中通告的 {@code window} 是 {@code rcvWnd >> rcvWscale}。</li>
 * </ul>
 *
 * <p>本测试验证 TcpHandshaker 在 WS 协商路径下的字段传递(未 WS 协商时二者都为 0)。
 */
class WindowScaleNegotiationTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;
    private static final int CLIENT_WSCALE = 5;
    private static final int SERVER_WSCALE = 7;  // TcpConfig.windowScale() 默认值

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
    @DisplayName("带 WS 选项的 SYN → 栈协商 WS,sndWscale=客户端值,rcvWscale=本端默认")
    void wsNegotiationSetsScaleFields() {
        // SYN 携带 MSS + WS=5 选项
        byte[] synOpts = mssAndWsOptions(1460, CLIENT_WSCALE);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN)
                .flags(PacketFactory.TCP_FLAG_SYN)
                .options(synOpts)
                .build());

        // 读 SYN-ACK,验证栈也带了 WS 选项
        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        int isn;
        try {
            assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
            isn = synAck.tcpSeq();
            // 栈的 SYN-ACK 通告 window 应被 rcvWscale 缩放(即 >> 7)
            // 对此测试我们只验证协商字段,不断言 window 具体值(会受 initialRcvWnd 影响)。
            // 但可以验证 options 中有 WSCALE 块
            int serverWs = parseWScale(synAck.tcpOptionsSlice());
            assertThat(serverWs)
                    .as("server SYN-ACK must carry WS option")
                    .isEqualTo(SERVER_WSCALE);
        } finally {
            synAck.release();
        }

        // 完成 3WHS
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();

        TcpSock sock = initializer.handler().sock();
        assertThat(sock.sndWscale())
                .as("sndWscale captures peer (client) wscale")
                .isEqualTo(CLIENT_WSCALE);
        assertThat(sock.rcvWscale())
                .as("rcvWscale captures our own wscale")
                .isEqualTo(SERVER_WSCALE);
    }

    @Test
    @DisplayName("SYN 不带 WS → 不协商 WS,两侧 wscale 均为 0")
    void noWsOptionLeavesWscaleZero() {
        // SYN 只带 MSS,不带 WS
        byte[] synOpts = mssOnly(1460);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN)
                .flags(PacketFactory.TCP_FLAG_SYN)
                .options(synOpts)
                .build());

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        int isn = synAck.tcpSeq();
        // SYN-ACK 不应携带 WS(因为客户端没 offer)
        int serverWs = parseWScale(synAck.tcpOptionsSlice());
        synAck.release();
        assertThat(serverWs)
                .as("server omits WS when client didn't offer it")
                .isLessThan(0);

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();

        TcpSock sock = initializer.handler().sock();
        assertThat(sock.sndWscale()).as("sndWscale=0 without negotiation").isZero();
        assertThat(sock.rcvWscale()).as("rcvWscale=0 without negotiation").isZero();
    }

    // ---- helpers ----

    /** MSS(4B)+ WS NOP-aligned(4B)= 8 字节,4 字节对齐。 */
    private static byte[] mssAndWsOptions(int mss, int wscale) {
        byte[] o = new byte[8];
        // MSS
        o[0] = 0x02;  // Kind=2
        o[1] = 0x04;  // Length=4
        o[2] = (byte) (mss >>> 8);
        o[3] = (byte) mss;
        // WS
        o[4] = 0x01;  // NOP
        o[5] = 0x03;  // Kind=3
        o[6] = 0x03;  // Length=3
        o[7] = (byte) wscale;
        return o;
    }

    /** 仅 MSS(4B),已 4 字节对齐。 */
    private static byte[] mssOnly(int mss) {
        byte[] o = new byte[4];
        o[0] = 0x02;
        o[1] = 0x04;
        o[2] = (byte) (mss >>> 8);
        o[3] = (byte) mss;
        return o;
    }

    /** 从 options buffer 解析 WS,-1 表示未携带。 */
    private static int parseWScale(io.netty.buffer.ByteBuf opts) {
        int i = opts.readerIndex();
        int end = i + opts.readableBytes();
        while (i < end) {
            byte kind = opts.getByte(i);
            if (kind == 0) break;     // EOL
            if (kind == 1) { i++; continue; }  // NOP
            if (i + 1 >= end) break;
            int len = opts.getUnsignedByte(i + 1);
            if (len < 2 || i + len > end) break;
            if (kind == 3 && len == 3) {
                return opts.getUnsignedByte(i + 2);
            }
            i += len;
        }
        return -1;
    }
}
