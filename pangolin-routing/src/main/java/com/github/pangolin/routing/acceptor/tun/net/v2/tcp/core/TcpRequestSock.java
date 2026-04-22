package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public final class TcpRequestSock extends SockCommon {
    private final TcpSock listener;
    private final TcpHandshaker request;
    private ChannelFuture connectFuture;
    private Channel childChannel;
    private ChannelFutureListener handshakeCloseListener;
    private TcpPacketBuf synPacket;
    /**
     * 入站 SYN 的 Netty context — P2.1 起 {@link TcpSockInitializer#onRequest} 可能需要借它
     * 发 RST(DENY 路径)或 fallback 写 SYN-ACK。由 {@code tcp_v4_conn_request} 在 SYN 到达时填入。
     */
    private ChannelHandlerContext net;
    /**
     * Initializer 私有 attachment 单槽 — P2.1 起 {@link TcpSockInitializer} 可以在此
     * 挂一组自定义状态(Netty {@code AttributeMap} 风格)。core 不感知内容,只在
     * {@link TcpMultiplexer#inet_csk_destroy_sock(TcpRequestSock)} 前回调
     * {@link TcpSockInitializer#onRequestDestroyed} 让 initializer 清理。
     */
    private Object attachment;

    // ── request_sock (Linux include/net/request_sock.h) ────────────────
    /** Request 定时器槽 — 对应 {@code request_sock.rsk_timer}。 */
    public volatile Runnable rsk_timer;
    /** 绝对到期时间戳 (ms) — 对应 {@code request_sock.timeout}。 */
    public long timeout;

    // ── inet_request_sock (include/net/inet_sock.h) ────────────────────
    /** MSS 协商结果 — 对应 {@code inet_request_sock.mss}。 */
    public int mss;
    /** 对端接收窗口缩放因子 — 对应 {@code inet_request_sock.snd_wscale}。 */
    public int snd_wscale;
    /** 本端接收窗口缩放因子 — 对应 {@code inet_request_sock.rcv_wscale}。 */
    public int rcv_wscale;
    /** Window Scaling 是否协商成功。 */
    public boolean wscale_ok;
    /** Timestamps 是否协商成功。 */
    public boolean tstamp_ok;

    // ── tcp_request_sock (include/linux/tcp.h) ─────────────────────────
    /** 微秒级时间戳协商标志 — 对应 {@code tcp_request_sock.req_usec_ts}。 */
    public boolean req_usec_ts;
    /** 客户端 ISN — 对应 {@code tcp_request_sock.rcv_isn}。 */
    public int rcv_isn;
    /** 服务端 ISN — 对应 {@code tcp_request_sock.snt_isn}。 */
    public int snt_isn;
    /** Timestamps 随机偏移,防止 TSval 泄露启动时间 — 对应 {@code ts_off}。 */
    public long ts_off;
    /** 第一次发送 SYN-ACK 时携带的 TSval — 对应 {@code snt_tsval_first}。 */
    public int snt_tsval_first;
    /** 最近一次发送 SYN-ACK 时携带的 TSval — 对应 {@code snt_tsval_last}。 */
    public int snt_tsval_last;
    /** 最近一次 OOW 挑战 ACK 时间戳 — 对应 {@code last_oow_ack_time}。 */
    public int last_oow_ack_time;
    /** 已确认接收的下一个序号 — 对应 {@code tcp_request_sock.rcv_nxt}。 */
    public int rcv_nxt;
    /** SYN 报文中 TOS 字段 — 对应 {@code tcp_request_sock.syn_tos}。 */
    public int syn_tos;
    /** 来自 SYN 的初始发送窗口 — 对应 {@code tcp_request_sock.snd_wnd}。 */
    public int snd_wnd;
    /** SYN-ACK 已重传次数 (0 = 仅首发) — 对应 {@code tcp_request_sock.num_retrans}。 */
    public int num_retrans;
    /** 对端 SYN 中 TSval,用于初始化子 socket 的 ts_recent — 对应 {@code ts_recent}。 */
    public long ts_recent;

    public TcpRequestSock(FourTuple key, TcpSock listener, TcpHandshaker request) {
        super(key);
        this.listener = listener;
        this.request = request;
    }

    public TcpSock listener() {
        return listener;
    }

    public TcpHandshaker request() {
        return request;
    }

    public ChannelFuture connectFuture() {
        return connectFuture;
    }

    public void connectFuture(ChannelFuture connectFuture) {
        this.connectFuture = connectFuture;
    }

    public Channel childChannel() {
        return childChannel;
    }

    public void childChannel(Channel childChannel) {
        this.childChannel = childChannel;
    }

    public ChannelFutureListener handshakeCloseListener() {
        return handshakeCloseListener;
    }

    public void handshakeCloseListener(ChannelFutureListener handshakeCloseListener) {
        this.handshakeCloseListener = handshakeCloseListener;
    }

    public TcpPacketBuf synPacket() {
        return synPacket;
    }

    public void synPacket(TcpPacketBuf synPacket) {
        this.synPacket = synPacket;
    }

    public ChannelHandlerContext net() {
        return net;
    }

    public void net(ChannelHandlerContext net) {
        this.net = net;
    }

    public Object attachment() {
        return attachment;
    }

    public void attachment(Object attachment) {
        this.attachment = attachment;
    }

    @Override
    public TcpConnectionState state() {
        return TcpConnectionState.TCP_SYN_RECV;
    }

    @Override
    public void state(TcpConnectionState state) {
    }
}
