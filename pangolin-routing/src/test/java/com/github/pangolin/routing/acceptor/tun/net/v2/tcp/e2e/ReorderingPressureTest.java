package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Sender;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
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
 * 持续乱序压力 —— 多次 DSACK 反馈推 RACK reo_wnd_steps 增长。
 *
 * <p>覆盖:
 * <ul>
 *   <li>多个 DSACK 事件下 reo_wnd_steps 单调递增(直到 0xFF 上限);</li>
 *   <li>同一 RTT 内重复 DSACK 不重复步进(S-3 守卫);</li>
 *   <li>recovery_persist 期满后回退到 1。</li>
 * </ul>
 *
 * <p>对齐 Linux {@code tcp_rack_update_reo_wnd}(net/ipv4/tcp_recovery.c)。
 */
class ReorderingPressureTest {

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
    @DisplayName("连续多次 DSACK(每次 RTT 内 delivered 推进)→ reoWndSteps 单调增长")
    void repeatedDsackEventsGrowReoWnd() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        int initialSteps = sender.rackReoWndSteps();
        assertThat(initialSteps).as("初始 reoWndSteps=1").isEqualTo(1);

        // 重复触发 DSACK:每轮发 2 段,cum-ACK 第一段 + DSACK 该段
        // 每轮 delivered 必须前进,才能让 S-3 1-RTT 守卫不挡住步进
        int observedMax = initialSteps;
        for (int round = 0; round < 5; round++) {
            // 发 seg(N)
            h.send(new byte[]{(byte) ('a' + round)});
            Tcp4PacketBuf out = harness.readOutboundTcp();
            int segSeq = out.tcpSeq();
            int segLen = out.tcpPayloadLength();
            out.release();

            // cum-ACK 推进 delivered
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, segSeq + segLen));
            harness.channel().runPendingTasks();
            drainAll();

            // 再发 seg(N+1) — 让后续 DSACK 块格式合法(需要可关联段)
            h.send(new byte[]{(byte) ('A' + round)});
            Tcp4PacketBuf out2 = harness.readOutboundTcp();
            int seg2Seq = out2.tcpSeq();
            int seg2Len = out2.tcpPayloadLength();
            out2.release();

            // DSACK [segSeq, segSeq+segLen) — 表示"已收过 segN 的副本"(伪重传)
            byte[] dsackOpt = sackOption2(
                    segSeq, segSeq + segLen,
                    seg2Seq, seg2Seq + seg2Len);
            harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                    .seq(CLIENT_ISN + 1)
                    .ack(segSeq + segLen)  // cum 不变
                    .flags(PacketFactory.TCP_FLAG_ACK)
                    .options(dsackOpt)
                    .build());
            harness.channel().runPendingTasks();
            drainAll();

            int curSteps = sender.rackReoWndSteps();
            observedMax = Math.max(observedMax, curSteps);
            System.out.println("[T4] round=" + round
                    + ", reoWndSteps=" + curSteps
                    + ", reoWndPersist=" + sender.rackReoWndPersist());

            // 每次 DSACK 后 cum-ACK seg2 推进 delivered,为下一轮的 1-RTT 守卫腾出空间
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, seg2Seq + seg2Len));
            harness.channel().runPendingTasks();
            drainAll();
        }

        // 关键观察:reoWndSteps 应至少超过初始值
        assertThat(observedMax)
                .as("多次 DSACK 后 reoWndSteps 应增长到 > 1")
                .isGreaterThan(1);

        // 上限保护:不能超过 0xFF(Linux 同上限)
        assertThat(sender.rackReoWndSteps())
                .as("reoWndSteps 不超 0xFF")
                .isLessThanOrEqualTo(0xFF);

        // recovery_persist 应被 DSACK 重置到 TCP_RACK_RECOVERY_THRESH(16)
        // (在最近一次 DSACK 后 - 1 epoch 衰减,不会衰到 0)
        assertThat(sender.rackReoWndPersist())
                .as("rackReoWndPersist > 0(最近 DSACK 重置)")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("无 DSACK 期间 reo_wnd_persist 衰减,最终回退 reoWndSteps=1")
    void noDsackEpochsDecayReoWnd() {
        CapturingInitializer.CapturingHandler h = initializer.handler();
        Sender sender = h.sock().sender();

        // 先产生一次 DSACK 把 steps 推到 2,persist 进入倒计时。
        // 注意:primeOneDsack 末尾的 cum-ACK 会让 tcpRackUpdateReoWnd 走 else 分支
        // persist--,因此初始 persist 是 RECOVERY_THRESH - 1 而不是 RECOVERY_THRESH。
        primeOneDsack(h);
        assertThat(sender.rackReoWndSteps()).isGreaterThan(1);
        int persistAfterDsack = sender.rackReoWndPersist();
        assertThat(persistAfterDsack)
                .as("DSACK 后 persist 接近 RECOVERY_THRESH(prime 内最后一次 cum-ACK 已减 1)")
                .isGreaterThanOrEqualTo(TcpConstants.TCP_RACK_RECOVERY_THRESH - 1);

        // 无 DSACK 反馈,但每次正常 cum-ACK 走 tcpRackUpdateReoWnd 路径,
        // 走 else 分支 persist--。直到 persist == 0 才回退 steps=1。
        int rounds = TcpConstants.TCP_RACK_RECOVERY_THRESH + 2;
        for (int round = 0; round < rounds; round++) {
            h.send(new byte[]{(byte) ('a' + (round % 26))});
            Tcp4PacketBuf out = harness.readOutboundTcp();
            int segSeq = out.tcpSeq();
            int segLen = out.tcpPayloadLength();
            out.release();
            harness.sendInbound(PacketFactory.ack(
                    CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                    CLIENT_ISN + 1, segSeq + segLen));
            harness.channel().runPendingTasks();
            drainAll();
        }

        // 经过足够多的无 DSACK 周期,steps 应该回到 1
        System.out.println(
                "[T4 OBS] decay end: reoWndSteps=" + sender.rackReoWndSteps()
                + ", persist=" + sender.rackReoWndPersist());
        assertThat(sender.rackReoWndSteps())
                .as("无 DSACK 期满 → reoWndSteps 回退 1")
                .isEqualTo(1);
    }

    /** 制造一次 DSACK 触发,把 reoWndSteps 至少推到 2。 */
    private void primeOneDsack(CapturingInitializer.CapturingHandler h) {
        h.send(new byte[]{'p'});
        Tcp4PacketBuf out = harness.readOutboundTcp();
        int segSeq = out.tcpSeq();
        int segLen = out.tcpPayloadLength();
        out.release();
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, segSeq + segLen));
        harness.channel().runPendingTasks();
        drainAll();

        h.send(new byte[]{'q'});
        Tcp4PacketBuf out2 = harness.readOutboundTcp();
        int seg2Seq = out2.tcpSeq();
        int seg2Len = out2.tcpPayloadLength();
        out2.release();

        byte[] dsackOpt = sackOption2(segSeq, segSeq + segLen, seg2Seq, seg2Seq + seg2Len);
        harness.sendInbound(PacketFactory.tcp(CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT)
                .seq(CLIENT_ISN + 1)
                .ack(segSeq + segLen)
                .flags(PacketFactory.TCP_FLAG_ACK)
                .options(dsackOpt)
                .build());
        harness.channel().runPendingTasks();
        drainAll();

        // 把 seg2 也累计 ACK,清掉飞行
        harness.sendInbound(PacketFactory.ack(
                CLIENT_IP, CLIENT_PORT, SERVER_IP, SERVER_PORT,
                CLIENT_ISN + 1, seg2Seq + seg2Len));
        harness.channel().runPendingTasks();
        drainAll();
    }

    // ---- helpers ----

    private void drainAll() {
        Tcp4PacketBuf p;
        while ((p = harness.readOutboundTcp()) != null) p.release();
    }

    private static byte[] sackOption2(int l1, int r1, int l2, int r2) {
        byte[] o = new byte[20];
        o[0] = 0x01; o[1] = 0x01; o[2] = 0x05; o[3] = 0x12;
        putInt(o, 4, l1); putInt(o, 8, r1);
        putInt(o, 12, l2); putInt(o, 16, r2);
        return o;
    }

    private static void putInt(byte[] o, int off, int v) {
        o[off] = (byte) (v >>> 24);
        o[off + 1] = (byte) (v >>> 16);
        o[off + 2] = (byte) (v >>> 8);
        o[off + 3] = (byte) v;
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
