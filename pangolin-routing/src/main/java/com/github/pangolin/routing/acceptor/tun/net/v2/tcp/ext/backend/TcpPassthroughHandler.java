package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ext.backend;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * {@link TcpSockHandler} 的 backend 透传实现 — 把 sock 方向入站的 payload 原样
 * {@code writeAndFlush} 到后端连接,并在 sock 端发生 FIN/RST/destroy 时关 backend。
 *
 * <p>backend → sock 方向的反向通路不在本类实现,由
 * {@link TcpPassthroughInitializer#onEstablished} 在 backend pipeline 上挂入站适配器,
 * 直接交给 {@code TcpMultiplexer.tcp_sendmsg}(MSS 切片 / push 由栈内部处理)。
 *
 * <p>生命周期:由 {@link TcpPassthroughInitializer#onEstablished} 构造并挂到
 * {@code sock.handler}。连接销毁或对端 FIN/RST 时,TCP 栈回调 {@link #onSocketDestroyed}
 * / {@link #onReset},本类负责把 backend channel 关掉(不可重入)。
 *
 * <p><b>线程约束</b>:所有方法在 {@code sock.eventLoop()} 上被调用。backend Channel 选
 * 同 EL 以便原子可见。
 */
public final class TcpPassthroughHandler implements TcpSockHandler {

    private final Channel upstream;
    private boolean closed;

    public TcpPassthroughHandler(Channel upstream) {
        this.upstream = upstream;
    }

    public Channel backend() {
        return upstream;
    }

    @Override
    public void onInboundData(ByteBuf data) {
        if (closed || !upstream.isOpen()) {
            data.release();
            return;
        }
        upstream.writeAndFlush(data);
    }

    @Override
    public void onPeerFin() {
        // 对齐 v1 CLOSE_WAIT 下主动关 backend 的语义(原 tcp_data_queue line 505-508)。
        // 对端半关后 backend 侧也应收到 FIN 信号以推进上游 read shutdown。
        if (closed) {
            return;
        }
        if (upstream.isOpen()) {
            upstream.close();
        }
    }

    @Override
    public void onReset(Throwable cause) {
        forceClose();
    }

    @Override
    public void onWritabilityChanged() {
        // backend I/O 走 Netty 自身 channel,不需要额外水位通知
    }

    @Override
    public void onSocketDestroyed() {
        forceClose();
    }

    private void forceClose() {
        if (closed) {
            return;
        }
        closed = true;
        if (upstream.isOpen()) {
            upstream.close();
        }
    }
}
