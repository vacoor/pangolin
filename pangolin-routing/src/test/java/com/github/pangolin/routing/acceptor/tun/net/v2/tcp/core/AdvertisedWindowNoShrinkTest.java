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
 * 单元 / E2E:对齐 Linux {@code tcp_select_window} 的 "right-edge 不得回缩" 规则。
 *
 * <p>RFC 793 §3.7 / RFC 9293 §3.8.6 要求接收方通告窗口的右边沿不得回缩。
 * Linux 在 {@code tcp_select_window}(net/ipv4/tcp_output.c)的外层包装里
 * 实现:若 {@code new_win < cur_win},把 {@code new_win} 对齐到
 * {@code 1 << rcv_wscale} 的 {@code cur_win} 上(wscale=0 时步长为 1,
 * 等价于直接 fall back 到 {@code cur_win})。
 *
 * <p>v2 旧版 {@code selectAdvertisedWindow} 把 no-shrink 守卫错放在
 * {@code wscale != 0} 分支里,wscale=0 时新计算的小窗口会直接通告出去
 * → 通告窗口右边沿回缩。本测试验证修复后两种 wscale 都遵守 no-shrink。
 */
class AdvertisedWindowNoShrinkTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    private TcpStackHarness harness;
    private CapturingInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CapturingInitializer();
        harness = new TcpStackHarness(initializer);
        completeHandshakeNoWscale();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("wscale=0:freeSpace 跌破 curWin 时,selectAdvertisedWindow 不回缩(新值对齐到 curWin)")
    void noShrinkWhenWscaleZero() {
        TcpSock sock = initializer.handler().sock();
        Receiver receiver = sock.receiver();

        // 不带 WS 协商,sndWscale=rcvWscale=0
        assertThat(sock.rcvWscale()).as("无 WS 协商,wscale=0").isZero();

        // 第一次 select:正常算窗口,记下作为基准 curWin
        int firstWnd = TcpOutput.selectAdvertisedWindow(sock);
        // selectAdvertisedWindow 会写回 rcvWnd 字段;再读一次得到内部 curWin
        int curWin = receiver.rcvWnd();
        assertThat(curWin).as("首轮 select 后 rcvWnd 落定").isPositive();

        // 模拟 freeSpace 跌破 curWin:把 rcvBuf 直接缩小到一个远小于 curWin 的值。
        // 此时若没有 no-shrink 守卫,selectAdvertisedWindow 会返回一个小于 curWin 的
        // 新窗口并写回 rcvWnd → 通告窗口右边沿回缩(违反 RFC 793 §3.7)。
        receiver.rcvBuf(2048);

        int secondWnd = TcpOutput.selectAdvertisedWindow(sock);
        int newWin = receiver.rcvWnd();

        // 修复后:no-shrink 守卫即便 wscale=0 也生效,新写回的 rcvWnd 不得 < curWin
        assertThat(newWin)
                .as("wscale=0 也不得收缩 rcvWnd:newWin >= curWin")
                .isGreaterThanOrEqualTo(curWin);
        // 返回值(已 >> wscale=0,即原值)同样满足 no-shrink
        assertThat(secondWnd)
                .as("返回值同样不收缩")
                .isGreaterThanOrEqualTo(firstWnd);
    }

    // ---- helpers ----

    /** 不带 WS option 的 3WHS,使 rcvWscale=0。 */
    private int completeHandshakeNoWscale() {
        // 仅 MSS,不带 WS
        byte[] synOpts = new byte[]{0x02, 0x04, 0x05, (byte) 0xB4}; // MSS=1460
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN)
                .flags(PacketFactory.TCP_FLAG_SYN)
                .options(synOpts)
                .build());
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
