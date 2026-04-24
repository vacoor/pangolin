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

import java.util.Arrays;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E:MSS 分片 —— 对齐 Linux {@code tcp_sendmsg} 对 > MSS payload 的切片逻辑。
 *
 * <p>覆盖 R4.2b-4e 迁到 {@link Sender#sendmsg} 内部的切片路径:
 * <pre>
 *   while (offset < total) {
 *     int len = min(total - offset, mss);
 *     sock.queueSkb(new TcpSegment(slice, writeSeq, len, ACK, 0L));
 *     offset += len;
 *   }
 * </pre>
 *
 * <p>场景:发送 3×MSS 的 payload,栈应发出 3 个等长的数据段,seq 连续递增,
 * 累计 ACK 一次性确认全部时 packets_out 归零。
 */
class MssFragmentationTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;
    /** 默认 MSS(TcpConfig.builder() 的默认值,等于 TCP_MSS_DEFAULT=1460)。 */
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
    @DisplayName("发 3×MSS payload → 3 个 MSS 大小的段,seq 连续,ACK 全部后 packets_out=0")
    void payloadSplitsIntoMssSizedSegments() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // 3 × MSS 的 payload
        int total = MSS * 3;
        byte[] payload = new byte[total];
        for (int i = 0; i < total; i++) payload[i] = (byte) (i & 0xFF);
        h.send(payload);
        harness.channel().runPendingTasks();

        // 应读出 3 个段
        int[] segSeqs = new int[3];
        int[] segLens = new int[3];
        byte[][] segPayloads = new byte[3][];
        for (int i = 0; i < 3; i++) {
            Tcp4PacketBuf out = harness.readOutboundTcp();
            assertThat(out).as("segment " + i + " should be emitted").isNotNull();
            segSeqs[i] = out.tcpSeq();
            segLens[i] = out.tcpPayloadLength();
            segPayloads[i] = new byte[segLens[i]];
            out.tcpPayloadSlice().getBytes(0, segPayloads[i]);
            out.release();
        }

        // 每段 = MSS
        for (int i = 0; i < 3; i++) {
            assertThat(segLens[i])
                    .as("segment " + i + " length = MSS")
                    .isEqualTo(MSS);
        }
        // seq 连续
        assertThat(segSeqs[0]).isEqualTo(serverIsn + 1);
        assertThat(segSeqs[1]).isEqualTo(segSeqs[0] + MSS);
        assertThat(segSeqs[2]).isEqualTo(segSeqs[1] + MSS);

        // payload 内容拼接后与原始一致
        byte[] rebuilt = new byte[total];
        int pos = 0;
        for (byte[] seg : segPayloads) {
            System.arraycopy(seg, 0, rebuilt, pos, seg.length);
            pos += seg.length;
        }
        assertThat(rebuilt).as("concatenated payload matches original").isEqualTo(payload);

        // 此时 3 段在 RTX 队列
        assertThat(sender.packetsOut()).isEqualTo(3);

        // 对端一次性 ACK 全部
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, segSeqs[2] + MSS));
        harness.channel().runPendingTasks();

        assertThat(sender.packetsOut())
                .as("single cumulative ACK releases all 3 segments")
                .isZero();
    }

    @Test
    @DisplayName("发 MSS+1 字节 → 2 段(一个 MSS,一个 1 字节)")
    void payloadJustOverMssSplitsIntoTwo() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        byte[] payload = new byte[MSS + 1];
        Arrays.fill(payload, (byte) 'A');
        payload[MSS] = (byte) 'B';  // 最后一字节不同,便于断言

        h.send(payload);
        harness.channel().runPendingTasks();

        Tcp4PacketBuf seg1 = harness.readOutboundTcp();
        Tcp4PacketBuf seg2 = harness.readOutboundTcp();
        try {
            assertThat(seg1.tcpPayloadLength())
                    .as("first segment = MSS bytes")
                    .isEqualTo(MSS);
            assertThat(seg2.tcpPayloadLength())
                    .as("second segment = 1 remainder byte")
                    .isEqualTo(1);
            // seq 连续
            assertThat(seg2.tcpSeq()).isEqualTo(seg1.tcpSeq() + MSS);
            // 最后一个字节应为 'B'
            byte[] tail = new byte[1];
            seg2.tcpPayloadSlice().getBytes(0, tail);
            assertThat(tail[0]).isEqualTo((byte) 'B');
        } finally {
            seg1.release();
            seg2.release();
        }
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
