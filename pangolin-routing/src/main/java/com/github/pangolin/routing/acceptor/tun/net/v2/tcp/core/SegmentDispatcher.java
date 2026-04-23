package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.SHUTDOWN_MASK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_MSS_DEFAULT;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.between;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps.oowRateLimited;

/**
 * v2 TCP 栈入站段分派器 + FSM 主入口。R4.2b-3 重命名自 {@code TcpMultiplexer}。
 *
 * <p><b>职责</b>:
 * <ul>
 *   <li>入站路由(abstract):{@link #rcv} / {@link #send_reset} /
 *       {@link #inet_rtx_syn_ack} / {@link #conn_request} / {@link #sendSynAck} /
 *       {@link #syn_recv_sock},由 {@link Ipv4SegmentDispatcher} 实现;</li>
 *   <li>FSM 处理(concrete,R4.2b-4 下沉):{@code checkReq} / {@code ackIncoming} /
 *       {@code dataQueue} / {@code finIncoming} / {@code outOfWindow} 等;</li>
 *   <li>per-sock 装配:{@link #configure} 建立 {@link TcpSock#multiplexer()} 反向引用
 *       + 创建 {@link Sender} / {@link Receiver}。</li>
 * </ul>
 *
 * <p><b>继承关系</b>:{@code extends} {@link TcpStack}(R4.2b-3 暂留,R4.2b-4 改组合)。
 * registries / 全局组件 / 生命周期 API 均从 {@link TcpStack} 继承。
 *
 * <p><b>架构三元</b>(对齐 gVisor endpoint + sender + receiver):
 * <pre>
 *   TcpSock (= endpoint: 控制块 + FSM)
 *     ├── sender   (Sender: cwnd/rto/push/retransmit 的统一入口)
 *     └── receiver (Receiver: rcvWnd/OFO/quickack 的统一入口)
 * </pre>
 *
 * <p><b>子类</b>:当前只有 {@link Ipv4SegmentDispatcher}(IPv4);IPv6 未实现。
 */
@Slf4j
public abstract class SegmentDispatcher extends TcpStack {

    protected SegmentDispatcher(TcpConfig config, EventLoopGroup tcpGroup, TcpSockInitializer initializer) {
        super(config, tcpGroup, initializer);
        init();
    }

    protected void init() {
        TcpSock listenSk = init(new TcpSock());
        listenSk.state(TcpConnectionState.TCP_LISTEN);
        this.listener = new Listener(listenSk, DEFAULT_MAX_SYN_BACKLOG);
        this.lookup = new SockLookup(establishedRegistry, timewaitRegistry, listener);
    }

    /**
     * Sock 装配钩子 — 所有 sock(listen / child / established)创建后必须经过本方法。
     * 默认实现:调 {@link #configure(TcpSock)} 注入 multiplexer / sender / receiver
     * 反向引用。子类若需要额外装配(如 IPv4 / IPv6 特定字段),应 {@code super.init(sk)}
     * 后再补自身逻辑。
     */
    protected TcpSock init(TcpSock sk) {
        return configure(sk);
    }

    /**
     * Per-sock 注入 per-stack 服务。建立 {@link TcpSock#multiplexer()} 反向引用,
     * 创建 {@link Sender} / {@link Receiver} 并挂入 sock。本方法由 {@link #init(TcpSock)}
     * 调用,外部代码通常不需要直接调。幂等 — 多次调用会覆盖 sender/receiver,但实际
     * 调用路径(listen sock / tcp_v4_syn_recv_sock)保证只经过一次。
     *
     * <p>R4.2b-2:configure 留在 SegmentDispatcher 而非 TcpStack,因为依赖
     * {@code sk.multiplexer(this)} 的 'this' 是 SegmentDispatcher 类型。R4.2b-3 重命名
     * {@code sk.multiplexer} → {@code sk.stack} 后可上移。
     */
    public TcpSock configure(TcpSock sk) {
        if (sk != null) {
            sk.multiplexer(this);
            // 幂等:createChild 可能已装配 sender/receiver 用于预填充字段(R2.3/R3.2),
            // 本方法不重建,避免覆盖已填好的状态。
            if (sk.sender() == null) sk.sender(new Sender(sk));
            if (sk.receiver() == null) sk.receiver(new Receiver(sk));
        }
        return sk;
    }

    public abstract void rcv(ChannelHandlerContext net, TcpPacketBuf pkt);

    public abstract void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err);

    public abstract void inet_rtx_syn_ack(ChannelHandlerContext net, TcpSock listenSock, TcpRequestSock req);

    protected abstract TcpRequestSock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt);

    /**
     * 发 SYN-ACK 响应 — 由 {@link TcpSockInitializer#onRequest} 决定何时调用。实现方
     * (如 {@code Ipv4SegmentDispatcher})在此装 {@code synAckFailureAction} /
     * {@code handshakeCloseListener} 并发送 SYN-ACK,启动 SYN-ACK 重传 timer。
     * synPacket 已由 {@code tcp_v4_conn_request} retain 一次,本方法不再参与 retain。
     *
     * <p><b>契约</b>:本方法只应被调一次;{@code TcpPassthroughInitializer} 在 backend connect
     * 成功后调用,工厂 / Raw initializer 在 onRequest 默认实现里立即调用,DENY 不调用。
     */
    public abstract void sendSynAck(TcpRequestSock req);

    protected abstract TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, TcpRequestSock req);

    public void consume(final ChannelHandlerContext ctx, final TcpPacketBuf pkt) {
        rcv(ctx, pkt);
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        TcpSock sk = establishedRegistry.get(key);
        if (sk == null || !sk.hasConnection() || !sk.state().canSend()) {
            data.release();
            return false;
        }
        sendmsg(sk, data, true);
        return true;
    }


    protected void initTransfer(TcpSock sk) {
        if (sk == null) {
            return;
        }
        sk.probeTimerAction(sk.sender()::probeTimer);
        sk.keepaliveTimerAction(sk.sender()::keepaliveTimer);

        /*
         * 统一走 initializer.onEstablished(sk, this) — initializer 构造期已 requireNonNull:
         * - TcpChannelInitializer:创建 TcpChannel,用户 pipeline 接管 payload
         * - TcpPassthroughInitializer (ext.backend):backend 透传,挂 TcpPassthroughHandler 并装反向适配器
         * - TcpSockInitializer.DENY:onRequest 阶段已直接发 RST 销毁 req,此路径不会触发
         */
        initializer.onEstablished(sk, this);
        sk.sender().armKeepalive(sk.keepaliveTimeMs());
    }

    /**
     * 应用层 payload 入发送队列入口 — delegate 到 {@link Sender#sendmsg}(R4.2b-4e 下沉)。
     * 保留 Dispatcher 层入口给 user API({@link #write})和测试 harness 用。
     */
    public void sendmsg(TcpSock sk, ByteBuf data, boolean flush) {
        sk.sender().sendmsg(data, flush);
    }

}
