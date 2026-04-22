package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.backend;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRequestSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.ReferenceCountUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * {@link TcpSockInitializer} 的 backend 透传实现 — 覆盖 {@code onRequest} 以在 SYN 阶段
 * 先连 backend 再发 SYN-ACK,覆盖 {@code onEstablished} 以装 {@link TcpPassthroughHandler}
 * 和反向适配器({@link TcpMultiplexer#tcp_sendmsg} — MSS 切片由栈内部处理),实现双向透传。
 *
 * <p><b>生命周期</b>:
 * <ol>
 *   <li>{@link #onRequest} — 半连接入队后,从 SYN pkt 解析目标地址,异步发起 backend connect。
 *       连上后把 backend Channel 写到 {@link TcpRequestSock#childChannel(Channel)} 并
 *       调 {@link TcpMultiplexer#sendSynAck(TcpRequestSock)};连接失败发 RST 并
 *       {@code inet_csk_destroy_sock(req)}。</li>
 *   <li>{@link #proposeEventLoop} — 为 child sock 推荐 backend 的 EL,保留 v1
 *       "状态机与 backend I/O 同线程" 语义。</li>
 *   <li>{@link #onEstablished} — 3WHS 完成后装 {@link TcpPassthroughHandler} 接管
 *       sock → backend 方向,并在 backend pipeline 末尾挂反向适配器。</li>
 * </ol>
 *
 * <p><b>EventLoop</b>:backend Channel 的 EL 由构造传入的 {@code childGroup.next()} 分配;
 * {@code tcp_v4_syn_recv_sock} 取这同一个 EL 作为 child sock 的 EL,保证两者同线程。
 */
public final class TcpPassthroughInitializer implements TcpSockInitializer {

    private final SocketChannelFactory socketChannelFactory;
    private final EventLoopGroup childGroup;
    private final int connTimeoutMs;

    public TcpPassthroughInitializer(SocketChannelFactory socketChannelFactory,
                                   EventLoopGroup childGroup,
                                   int connTimeoutMs) {
        this.socketChannelFactory = Objects.requireNonNull(socketChannelFactory, "socketChannelFactory");
        this.childGroup = Objects.requireNonNull(childGroup, "childGroup");
        this.connTimeoutMs = connTimeoutMs;
    }

    /**
     * SYN 阶段:先连 backend,连上再发 SYN-ACK。
     *
     * <p><b>前置条件</b>:{@code tcp_v4_conn_request} 已完成 {@code req.net(net)} 和
     * {@code req.synPacket(pkt.retain())},本方法直接读取 req 上的字段,不参与 retain/release
     * (lifetime 由 tcp_v4_conn_request / moveToEstablished / inet_csk_destroy_sock 托管)。
     */
    @Override
    public void onRequest(final TcpRequestSock req, final TcpMultiplexer multiplexer) {
        final ChannelHandlerContext net = req.net();
        final TcpPacketBuf synPkt = req.synPacket();
        final InetSocketAddress target = resolveTarget(synPkt);

        req.connectFuture(socketChannelFactory.open(target, connTimeoutMs, false, childGroup,
                new ChannelInboundHandlerAdapter() {
                }).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                req.childChannel(future.channel());
                // 由 TcpMultiplexer 装 synAckFailureAction / handshakeCloseListener 并触发 SYN-ACK。
                multiplexer.sendSynAck(req);
                // sendSynAck 装完 handshakeCloseListener 后,绑到 backend 关闭事件上,
                // 对齐 v1 "backend close → RST + destroy req" 的 handshake 期语义。
                future.channel().closeFuture().addListener(req.handshakeCloseListener());
            } else {
                if (req.synPacket() != null) {
                    req.request().sendResetAndAbort(net.channel(), req.synPacket());
                }
                multiplexer.inet_csk_destroy_sock(req);
            }
        }));
    }

    /**
     * 推荐 child sock 的 EL:backend Channel 未连通时(不可能进入 tcp_v4_syn_recv_sock,
     * 防御性返回 null)或已连通时返回其 EL,让 child sock 与 backend I/O 同线程。
     */
    @Override
    public EventLoop proposeEventLoop(TcpRequestSock req, TcpMultiplexer multiplexer) {
        final Channel backend = req.childChannel();
        return backend == null ? null : backend.eventLoop();
    }

    @Override
    public void onEstablished(final TcpSock sock, final TcpMultiplexer multiplexer) {
        final Channel backend = Objects.requireNonNull(sock.childChannel(), "backend channel");

        // 1. 装 sock 端 handler — consume 走这条路进 backend
        sock.handler(new TcpPassthroughHandler(backend));

        // 2. 替换 childCloseListener:握手阶段的 listener 已由 TcpSock 持有(handshake
        //    失败时 RST + destroy req),切入 ESTABLISHED 后换成"backend 关 → sock 推 FIN"。
        final ChannelFutureListener handshakeCloseListener = sock.childCloseListener();
        sock.childCloseListener(future -> {
            if (multiplexer.tcp_close_state(sock)) {
                TcpOutput.INSTANCE.tcp_send_fin(sock);
            }
        });
        backend.closeFuture().addListener(sock.childCloseListener());
        if (handshakeCloseListener != null) {
            backend.closeFuture().removeListener(handshakeCloseListener);
        }

        // 3. 挂反向适配器:backend payload → tcp_sendmsg(MSS 切片 / push 由栈内部处理)
        backend.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (!(msg instanceof ByteBuf)) {
                    ReferenceCountUtil.release(msg);
                    return;
                }
                // 引用权交给 tcp_sendmsg,其内部负责 release
                multiplexer.tcp_sendmsg(sock, (ByteBuf) msg, true);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                TcpOutput.INSTANCE.tcp_send_reset(sock);
                multiplexer.tcp_done(sock);
                if (ctx.channel().isOpen()) {
                    ctx.channel().close();
                }
            }
        });
        backend.config().setAutoRead(true);
    }

    private static InetSocketAddress resolveTarget(TcpPacketBuf pkt) {
        final InetAddress resolved = pkt.resolvedDstAddr() != null ? pkt.resolvedDstAddr() : pkt.dstAddr();
        final String host = resolved.getHostName();
        if (!resolved.getHostAddress().equals(host)) {
            return InetSocketAddress.createUnresolved(host, pkt.tcpDstPort());
        }
        return new InetSocketAddress(resolved, pkt.tcpDstPort());
    }
}
