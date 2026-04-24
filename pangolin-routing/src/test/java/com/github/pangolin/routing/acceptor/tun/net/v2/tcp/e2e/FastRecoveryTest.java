package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
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
 * E2E:Fast Retransmit / Fast Recovery(对齐 Linux
 * {@code tcp_ack → tcp_fastretrans_alert → tcp_enter_recovery})。
 *
 * <p>当本端连续收到 3 个 duplicate ACK(同一 ack_num,不推进 SND.UNA)且 CC 状态
 * 为 OPEN 时:
 * <ul>
 *   <li>快照 cwnd / ssthresh / snd_una(tcpInitUndo)</li>
 *   <li>ssthresh = max(cwnd / 2, 2)</li>
 *   <li>cwnd = ssthresh + 3(Fast Retransmit 的 inflation:3 代表三个 dupack 暗示的 inflight)</li>
 *   <li>highSeq = sndNxt</li>
 *   <li>CC state → RECOVERY</li>
 *   <li>tcp_mark_head_lost(1):队首段记为 LOST</li>
 *   <li>retransmit():立即重传队首段</li>
 * </ul>
 *
 * <p>覆盖 R2.3 / R7.2 之后留在 TcpSock 的 {@code onAckedByCc} 核心分支,
 * 以及 {@link Sender} 的 dupacks / cwnd / ssthresh / congestionState 字段协同。
 */
class FastRecoveryTest {

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
    @DisplayName("3 个 dup ACK → 进 RECOVERY + cwnd 减半 + 重传队首段")
    void threeDupacksEnterRecovery() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        TcpSock sock = h.sock();
        Sender sender = sock.sender();

        // 栈发一段 payload;此段将持续未 ACK,被 3 次 dup ACK "钉"住
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);

        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        assertThat(origOut).isNotNull();
        int origSeq = origOut.tcpSeq();
        origOut.release();

        // 初始 CC 状态:OPEN,dupacks=0
        assertThat(sock.isCaOpen())
                .as("initial CC state is OPEN")
                .isTrue();
        assertThat(sender.dupacks()).isZero();

        int initialCwnd = sender.cwnd();
        int initialSsthresh = sender.ssthresh();
        int expectedNewSsthresh = Math.max(initialCwnd / 2, 2);
        int expectedNewCwnd = expectedNewSsthresh + 3;

        // 发 3 个完全相同的 dup ACK(ack_num = origSeq,不推进 SND.UNA)
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();

        // 第 3 个 dupack 触发 RECOVERY 迁移
        assertThat(sock.inRecovery())
                .as("3 dupacks should flip CC state to RECOVERY")
                .isTrue();

        // ssthresh 减半
        assertThat(sender.ssthresh())
                .as("ssthresh should halve to max(cwnd/2, 2)")
                .isEqualTo(expectedNewSsthresh);

        // cwnd 膨胀到 ssthresh + 3
        assertThat(sender.cwnd())
                .as("cwnd inflates to ssthresh + 3 on Fast Retransmit entry")
                .isEqualTo(expectedNewCwnd);

        // highSeq 锁到进入 Recovery 时的 sndNxt
        assertThat(sender.highSeq())
                .as("highSeq captures sndNxt at Recovery entry")
                .isEqualTo(sender.sndNxt());

        // 应出一个 Fast Retransmit 段,seq = 队首段(= origSeq)
        Tcp4PacketBuf fastRtx = drainForSeq(origSeq);
        assertThat(fastRtx)
                .as("fast retransmit of the head segment should be emitted")
                .isNotNull();
        try {
            assertThat(fastRtx.tcpPayloadLength()).isEqualTo(payload.length);
        } finally {
            fastRtx.release();
        }
    }

    @Test
    @DisplayName("RECOVERY 中,新的 dupacks 继续到达:cwnd 递增(inflation),highSeq 不变")
    void dupacksInRecoveryInflateCwnd() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        h.send(payload);
        Tcp4PacketBuf origOut = harness.readOutboundTcp();
        int origSeq = origOut.tcpSeq();
        origOut.release();

        // 触发 RECOVERY
        for (int i = 0; i < 3; i++) {
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, origSeq));
        }
        harness.channel().runPendingTasks();
        drainAll();
        assertThat(h.sock().inRecovery()).isTrue();

        int cwndAtEntry = sender.cwnd();
        int highSeqAtEntry = sender.highSeq();

        // 继续在 Recovery 中再收一个 dupack:走 incrementCwnd 分支(NewReno 的 cwnd inflation)
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, origSeq));
        harness.channel().runPendingTasks();
        drainAll();

        assertThat(h.sock().inRecovery())
                .as("still in RECOVERY after extra dupack")
                .isTrue();
        assertThat(sender.cwnd())
                .as("extra dupack during Recovery inflates cwnd (Linux tcp_cwnd_inflate)")
                .isGreaterThan(cwndAtEntry);
        assertThat(sender.highSeq())
                .as("highSeq is a snapshot at entry; dupacks in Recovery don't change it")
                .isEqualTo(highSeqAtEntry);
    }

    // ---- helpers ----

    /** 从出站队列找到第一个 seq=expected 的段;其余释放。 */
    private Tcp4PacketBuf drainForSeq(int expected) {
        for (int i = 0; i < 8; i++) {
            Tcp4PacketBuf p = harness.readOutboundTcp();
            if (p == null) return null;
            if (p.tcpSeq() == expected && p.tcpPayloadLength() > 0) {
                return p;
            }
            p.release();
        }
        return null;
    }

    private void drainAll() {
        Tcp4PacketBuf p;
        while ((p = harness.readOutboundTcp()) != null) p.release();
    }

    private int completeHandshake() {
        harness.sendInbound(PacketFactory.syn(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT, CLIENT_ISN));

        Tcp4PacketBuf synAck = harness.readOutboundTcp();
        assertThat(synAck).isNotNull();
        assertThat(synAck.isSyn() && synAck.isAck()).isTrue();
        int isn = synAck.tcpSeq();
        synAck.release();

        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, isn + 1));
        harness.channel().runPendingTasks();
        return isn;
    }
}
