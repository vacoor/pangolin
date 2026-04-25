package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

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
 * 对齐 Linux {@code tcp_send_challenge_ack}(net/ipv4/tcp_input.c)的限速行为:
 * <ul>
 *   <li>per-socket {@code tp->last_oow_ack_time} 仅服务于
 *       {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps#oowRateLimited}
 *       的 OOW 路径,**不应**抑制 challenge ACK;</li>
 *   <li>challenge ACK 只受 netns 桶({@code sysctl_tcp_challenge_ack_limit},默认 1000/s)约束。</li>
 * </ul>
 */
class ChallengeAckRateLimitTest {

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
    @DisplayName("per-socket OOW 时戳已设 → challenge ACK 不应受其抑制(对齐 Linux 两套独立桶)")
    void challengeAckIgnoresPerSocketOowTimestamp() {
        TcpSock sock = initializer.handler().sock();
        // 模拟"上一刻刚发过一个 OOW ACK"——把 lastOowAckTimeMs 设为当前时戳
        sock.lastOowAckTimeMs(com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32());

        // drain 握手 ACK 噪声
        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) out.release();

        sock.stack().output().sendChallengeAck(sock, false);
        harness.channel().runPendingTasks();

        Tcp4PacketBuf ack = harness.readOutboundTcp();
        assertThat(ack)
                .as("S3:per-socket OOW 时戳不应屏蔽 challenge ACK")
                .isNotNull();
        ack.release();
    }

    @Test
    @DisplayName("challenge ACK 不应写入 lastOowAckTimeMs(对齐 Linux:两套桶互不污染)")
    void challengeAckDoesNotPolluteOowTimestamp() {
        TcpSock sock = initializer.handler().sock();
        sock.lastOowAckTimeMs(0L);

        // drain 握手 ACK 噪声
        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) out.release();

        sock.stack().output().sendChallengeAck(sock, false);
        harness.channel().runPendingTasks();

        Tcp4PacketBuf ack = harness.readOutboundTcp();
        assertThat(ack).isNotNull();
        ack.release();

        assertThat(sock.lastOowAckTimeMs())
                .as("S3:challenge ACK 不应给 OOW 桶打时戳")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("netns 桶耗尽 → 后续 challenge ACK 静默吞掉(host 桶仍生效)")
    void hostBucketStillBoundsChallengeAck() {
        TcpSock sock = initializer.handler().sock();

        // drain 握手 ACK 噪声
        Tcp4PacketBuf out;
        while ((out = harness.readOutboundTcp()) != null) out.release();

        // 临时把 host 桶阈值压到 3,便于在 1s 窗口内手动打满
        int saved = SysctlOptions.sysctl_tcp_challenge_ack_limit;
        SysctlOptions.sysctl_tcp_challenge_ack_limit = 3;
        try {
            int sent = 0;
            for (int i = 0; i < 10; i++) {
                sock.stack().output().sendChallengeAck(sock, false);
                harness.channel().runPendingTasks();
                Tcp4PacketBuf ack = harness.readOutboundTcp();
                if (ack != null) {
                    sent++;
                    ack.release();
                }
            }
            assertThat(sent)
                    .as("S1:阈值改写后,1s 内最多发出 sysctl_tcp_challenge_ack_limit 个")
                    .isLessThanOrEqualTo(3);
            assertThat(sent)
                    .as("阈值 > 0 时至少应发出 1 个")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            SysctlOptions.sysctl_tcp_challenge_ack_limit = saved;
        }
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
