package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.e2e;

import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SegmentDispatcher;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRequestSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.PacketFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.CLIENT_IP;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness.TcpStackHarness.SERVER_IP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复现 passthrough 模式下"backend 回写一份数据 → 对端 wire 看到两份相同 DATA"的现象。
 *
 * <p>用 EmbeddedChannel 模拟 backend,与 TcpPassthroughInitializer 同样的反向 pipeline
 * 接到 v2 sock 上,验证一次 backend.writeAndFlush 是否在 wire 上产出 1 个还是 2 个相同
 * payload 的 TCP 段。
 */
class PassthroughDuplicateRespTest {

    private static final int CLIENT_PORT = 12345;
    private static final int SERVER_PORT = 80;
    private static final int CLIENT_ISN = 1000;

    private TcpStackHarness harness;
    private CapturingPassthroughInit initializer;
    private int serverIsn;

    @BeforeEach
    void setUp() {
        initializer = new CapturingPassthroughInit();
        harness = new TcpStackHarness(initializer);
        serverIsn = completeHandshake();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("Passthrough(直调 sendmsg):backend 回写一份 → wire 上只有一个 DATA 段")
    void singleSendmsgEmitsSingleSegment() {
        TcpSock sock = initializer.sock;
        assertThat(sock).isNotNull();

        byte[] respBytes = "pong-response".getBytes(StandardCharsets.UTF_8);
        sock.sender().sendmsg(Unpooled.wrappedBuffer(respBytes), true);
        harness.channel().runPendingTasks();

        List<int[]> dataSegs = collectOutboundData();
        System.out.println("[PassthroughDup-A] outbound DATA seg count = " + dataSegs.size());
        for (int[] s : dataSegs) {
            System.out.println("[PassthroughDup-A]   seq=" + s[0] + " len=" + s[1]);
        }
        assertThat(dataSegs.size())
                .as("直调 sendmsg → 1 个段")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("writeXmit 后 writeQueue 应为空,rtxQueue 有刚发送的段(用户假设核验)")
    void afterWriteXmitWriteQueueEmptyRtxHasSent() {
        TcpSock sock = initializer.sock;
        assertThat(sock).isNotNull();

        byte[] respBytes = "verify-queue-after-xmit".getBytes(StandardCharsets.UTF_8);
        int writeBefore = sock.sendBuffer().writeQueueSize();
        int rtxBefore = sock.sendBuffer().rtxQueueSize();
        System.out.println("[T-Q] before sendmsg: write=" + writeBefore + " rtx=" + rtxBefore);

        sock.sender().sendmsg(Unpooled.wrappedBuffer(respBytes), true);
        harness.channel().runPendingTasks();

        int writeAfter = sock.sendBuffer().writeQueueSize();
        int rtxAfter = sock.sendBuffer().rtxQueueSize();
        System.out.println("[T-Q] after sendmsg+flush: write=" + writeAfter + " rtx=" + rtxAfter
                + " packetsOut=" + sock.packetsOut());

        assertThat(writeAfter)
                .as("已发段应已从 writeQueue 出队(用户假设的反例)")
                .isZero();
        assertThat(rtxAfter)
                .as("已发段应进入 rtxQueue")
                .isGreaterThan(rtxBefore);

        // 收一遍 outbound 看 wire 上几个 DATA 段
        List<int[]> dataSegs = collectOutboundData();
        System.out.println("[T-Q] outbound DATA seg count = " + dataSegs.size());
        assertThat(dataSegs.size()).as("wire 应见一个 DATA 段").isEqualTo(1);
    }

    @Test
    @DisplayName("Passthrough(走 reverse adapter pipeline):backend.writeAndFlush 一份 → wire 应只见一个 DATA 段")
    void backendWriteAndFlushViaReverseAdapterEmitsSingleSegment() {
        TcpSock sock = initializer.sock;
        EmbeddedChannel backend = initializer.backend;
        assertThat(sock).isNotNull();
        assertThat(backend).isNotNull();

        // 与 TcpPassthroughInitializer.onEstablished L120 完全一致:
        // 在 backend pipeline 末尾装一个 inbound handler,把 channelRead 的 ByteBuf
        // 直接塞进 sock.sender().sendmsg。
        backend.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (!(msg instanceof ByteBuf)) {
                    ReferenceCountUtil.release(msg);
                    return;
                }
                sock.sender().sendmsg((ByteBuf) msg, true);
            }
        });

        // 模拟 backend 收到对端字节(走 inbound 方向通过 pipeline)
        byte[] respBytes = "pong-response-via-pipeline".getBytes(StandardCharsets.UTF_8);
        backend.writeInbound(Unpooled.wrappedBuffer(respBytes));
        backend.runPendingTasks();
        harness.channel().runPendingTasks();

        List<int[]> dataSegs = collectOutboundData();
        System.out.println("[PassthroughDup-B] outbound DATA seg count = " + dataSegs.size());
        for (int[] s : dataSegs) {
            System.out.println("[PassthroughDup-B]   seq=" + s[0] + " len=" + s[1]);
        }
        assertThat(dataSegs.size())
                .as("backend.writeInbound 一份 → wire 应只见 1 个 DATA 段")
                .isEqualTo(1);
        assertThat(dataSegs.get(0)[1])
                .as("段长度等于 payload")
                .isEqualTo(respBytes.length);
    }

    private List<int[]> collectOutboundData() {
        List<int[]> dataSegs = new ArrayList<>();
        Tcp4PacketBuf pkt;
        while ((pkt = harness.readOutboundTcp()) != null) {
            int len = pkt.tcpPayloadLength();
            if (len > 0) {
                dataSegs.add(new int[]{pkt.tcpSeq(), len});
            }
            pkt.release();
        }
        return dataSegs;
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

    /**
     * 简化版 passthrough initializer:不连真后端,只装一个 byte sink 当 backend handler。
     * onEstablished 后 sock.sender().sendmsg 路径与生产 TcpPassthroughInitializer 完全一致。
     */
    private static final class CapturingPassthroughInit implements TcpSockInitializer {
        TcpSock sock;
        EmbeddedChannel backend;

        @Override
        public void onRequest(TcpRequestSock req, SegmentDispatcher stack) {
            // 模拟 backend 已连上;立即触发 SYN-ACK
            backend = new EmbeddedChannel();
            req.childChannel(backend);
            stack.sendSynAck(req);
        }

        @Override
        public void onEstablished(TcpSock sock, SegmentDispatcher stack) {
            this.sock = sock;
            // 模仿 TcpPassthroughHandler:sock 入站直接转 backend
            sock.handler(new TcpSockHandler() {
                @Override public void onInboundData(ByteBuf data) { data.release(); }
                @Override public void onPeerFin() {}
                @Override public void onReset(Throwable cause) {}
                @Override public void onWritabilityChanged() {}
                @Override public void onSocketDestroyed() {}
            });
        }
    }
}
