package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpReceiveBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSendBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSkb;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRetransmitter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpHandshaker;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpHandshakerFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SysctlOptions;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimewaitSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionTimers;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.SHUTDOWN_MASK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_MSS_DEFAULT;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;

public final class TcpRequestSock extends SockCommon {
    private final TcpSock listener;
    private final TcpHandshaker request;
    private ChannelFuture connectFuture;
    private Channel childChannel;
    private ChannelFutureListener handshakeCloseListener;
    private TcpPacketBuf synPacket;

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

    @Override
    public TcpConnectionState state() {
        return TcpConnectionState.TCP_SYN_RECV;
    }

    @Override
    public void state(TcpConnectionState state) {
    }
}
