package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

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

import java.nio.charset.StandardCharsets;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:PAWS(Protection Against Wrapped Sequences,RFC 7323)—— TSval 倒退的段
 * 应被 {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingPreValidator}
 * 在进入 FSM 前拒绝,丢数据 + 立即 ACK,MIB 计数 {@code PAWSESTABREJECTED}。
 *
 * <p>覆盖:
 * <ul>
 *   <li>TS 选项在 3WHS 协商并落到 {@code ts_recent} 基线;</li>
 *   <li>后续合法 data(TSval 递增)正常交付;</li>
 *   <li>TSval 倒退超过 {@code TCP_PAWS_WINDOW} 的 data → PAWS 拒绝,payload 不到 handler,
 *       但栈立即回 ACK(带当前 rcv_nxt);</li>
 *   <li>验证 {@code TcpIncomingPreValidator} 刚修的"失败分支不可双释放"回归 ——
 *       PAWS 路径走 {@code validateIncoming} 的 paws-reject early-return。</li>
 * </ul>
 */
class PawsRejectionTest {

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
    @DisplayName("TSval 倒退的 data 段 → PAWS 拒绝,payload 不交付,栈立即回 ACK")
    void pawsRejectedDataNotDelivered() {
        // 握手带 TS(client TSval=100)
        int serverIsn = completeHandshakeWithTs(/*clientTsVal*/ 100);
        CapturingInitializer.CapturingHandler h = initializer.handler();
        int priorRcvNxt = h.sock().rcvNxt();
        assertThat(priorRcvNxt).isEqualTo(CLIENT_ISN + 1);

        // 合法递增 TSval=200 的 data:应被接受并交付
        ByteBuf payloadOk = Unpooled.wrappedBuffer("hi".getBytes(StandardCharsets.UTF_8));
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(serverIsn + 1)
                .flags(PacketFactory.TCP_FLAG_ACK | PacketFactory.TCP_FLAG_PSH)
                .options(tsOption(/*TSval*/ 200, /*TSecr*/ 0))
                .payload(payloadOk)
                .build());
        harness.channel().runPendingTasks();

        assertThat(h.inboundPayloads())
                .as("valid TSval segment should be delivered")
                .hasSize(1);
        drainOutbound();  // 清掉栈的 ACK(quickack 或 delayed)

        int advancedRcvNxt = h.sock().rcvNxt();
        assertThat(advancedRcvNxt).isEqualTo(priorRcvNxt + 2);

        // PAWS 倒退:TSval=50(远小于 ts_recent=200 的 TCP_PAWS_WINDOW=1)
        ByteBuf payloadPaws = Unpooled.wrappedBuffer("bad".getBytes(StandardCharsets.UTF_8));
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(advancedRcvNxt)
                .ack(serverIsn + 1)
                .flags(PacketFactory.TCP_FLAG_ACK | PacketFactory.TCP_FLAG_PSH)
                .options(tsOption(50, 0))
                .payload(payloadPaws)
                .build());
        harness.channel().runPendingTasks();

        // payload 不应被交付
        assertThat(h.inboundPayloads())
                .as("PAWS rejected segment must not deliver payload")
                .hasSize(1);
        // rcv_nxt 不应推进
        assertThat(h.sock().rcvNxt())
                .as("rcv_nxt must stay at prior when PAWS rejects")
                .isEqualTo(advancedRcvNxt);

        // 栈应立即回 ACK(validator 失败分支 sendAck)
        Tcp4PacketBuf ack = harness.readOutboundTcp();
        assertThat(ack).as("PAWS reject must still emit an ACK").isNotNull();
        try {
            assertThat(ack.isAck()).isTrue();
            assertThat(ack.isRst()).isFalse();
            assertThat(ack.tcpAckNum())
                    .as("ACK points to unchanged rcv_nxt")
                    .isEqualTo(advancedRcvNxt);
        } finally {
            ack.release();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════

    /** 构造 12 字节 TS 选项(NOP NOP Kind=8 Len=10 TSval TSecr),4 字节对齐。 */
    private static byte[] tsOption(int tsVal, int tsEcr) {
        byte[] o = new byte[12];
        o[0] = 0x01;                // NOP
        o[1] = 0x01;                // NOP
        o[2] = 0x08;                // Kind=8 (Timestamps)
        o[3] = 0x0A;                // Length=10
        o[4] = (byte) (tsVal >>> 24);
        o[5] = (byte) (tsVal >>> 16);
        o[6] = (byte) (tsVal >>> 8);
        o[7] = (byte) tsVal;
        o[8] = (byte) (tsEcr >>> 24);
        o[9] = (byte) (tsEcr >>> 16);
        o[10] = (byte) (tsEcr >>> 8);
        o[11] = (byte) tsEcr;
        return o;
    }

    /** 3WHS 带 TS 协商,返回 server ISN。 */
    private int completeHandshakeWithTs(int clientTsVal) {
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN)
                .flags(PacketFactory.TCP_FLAG_SYN)
                .options(tsOption(clientTsVal, 0))
                .build());

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).as("SYN-ACK with TS must be emitted").isNotNull();
        assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
        int isn = synAck.tcpSeq();
        // 解析 server 的 TSval(放在 SYN-ACK 的 TS option 里):客户端 ACK 的 TSecr
        // 必须精确等于 server 的 TSval,否则 checkReq 里 TSECR 范围校验会拒绝。
        long[] serverTs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec
                .parseTimestamp(synAck.tcpOptionsSlice());
        synAck.release();
        assertThat(serverTs).as("server SYN-ACK must carry TS option").isNotNull();
        int serverTsVal = (int) serverTs[0];

        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(isn + 1)
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(tsOption(clientTsVal + 1, serverTsVal))
                .build());
        harness.channel().runPendingTasks();
        return isn;
    }

    private void drainOutbound() {
        while (true) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return;
            p.release();
        }
    }
}
