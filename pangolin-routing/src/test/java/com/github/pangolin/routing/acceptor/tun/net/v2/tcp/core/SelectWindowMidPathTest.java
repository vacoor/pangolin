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
 * 单元测试:对齐 Linux {@code __tcp_select_window} 在
 * {@code free_space < full_space / 2} 中间分支的两条副作用。
 *
 * <pre>
 * if (free_space < (full_space >> 1)) {
 *     icsk->icsk_ack.quick = 0;            // 退出 quick-ACK
 *     free_space = round_down(free_space, mss);  // MSS 对齐
 *     if (free_space < allowed/16 || free_space < mss) {
 *         tp->rcv_wnd = 0;
 *         return 0;
 *     }
 * }
 * </pre>
 */
class SelectWindowMidPathTest {

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
    @DisplayName("free_space < full_space/2 → quickAck 清零(对齐 Linux 中间分支第一步)")
    void midBranchClearsQuickAck() {
        TcpSock sock = initializer.handler().sock();
        Receiver receiver = sock.receiver();

        // 进 quick-ACK 模式
        sock.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        assertThat(receiver.quickAckCount()).isPositive();

        // 把 rcvBuf 缩小,迫使 freeSpace < fullSpace/2
        receiver.rcvBuf(2 * MSS);
        receiver.windowClamp(2 * MSS);
        // 模拟 buffer 被占用一半以上:rmemAlloc 预留小于 fullSpace/2
        // 直接通过缩小 rcvBuf 就足够触发条件 —— freeSpace = winFromSpace(rcvBuf)
        // = 2*MSS * 0.8 = ~2336;fullSpace = 同 = ~2336;所以 freeSpace 等于
        // fullSpace 不满足 <。需要让 rmemAlloc > 0,这里通过反压让数据滞留。
        receiver.paused(true);
        io.netty.buffer.ByteBuf p = io.netty.buffer.Unpooled.buffer(MSS).writerIndex(MSS);
        harness.sendInbound(PacketFactory.data(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, serverIsn + 1, p));
        harness.channel().runPendingTasks();
        // 现在 rmemAlloc=MSS,rcvBuf=2*MSS,freeSpace ≈ MSS * 0.8 = 1168
        // fullSpace ≈ 2336,fullSpace/2 = 1168,freeSpace 边界值,可能恰相等
        // 实际取 winFromSpace 还有舍入 — 把 rcvBuf 进一步缩小确保 < 半值
        receiver.rcvBuf(MSS);  // 现在 fullSpace ≈ 1168,freeSpace ≈ 0(rmem=MSS 超出)

        // 触发 select
        TcpOutput.selectAdvertisedWindow(sock);

        // quick-ACK 应被清零
        assertThat(receiver.quickAckCount())
                .as("中间分支应清 quickAck(对齐 icsk->icsk_ack.quick = 0)")
                .isZero();

        // 同时该路径会通告零窗
        assertThat(receiver.rcvWnd())
                .as("freeSpace 几乎为零 → 通告零窗")
                .isZero();
    }

    @Test
    @DisplayName("free_space ≥ full_space/2 → 不触发清零")
    void noMidBranchKeepsQuickAck() {
        TcpSock sock = initializer.handler().sock();
        Receiver receiver = sock.receiver();
        sock.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        int before = receiver.quickAckCount();
        assertThat(before).isPositive();

        // 默认大缓冲,freeSpace 接近 fullSpace,不进中间分支
        TcpOutput.selectAdvertisedWindow(sock);

        assertThat(receiver.quickAckCount())
                .as("不触发中间分支,quickAckCount 保持")
                .isEqualTo(before);
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
