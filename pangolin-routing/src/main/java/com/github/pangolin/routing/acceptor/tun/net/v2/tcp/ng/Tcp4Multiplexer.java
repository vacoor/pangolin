package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.ng;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshaker;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.timer.TcpTimerScheduler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler.FLAG_NO_CHALLENGE_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler.FLAG_SLOWPATH;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpIncomingAckHandler.FLAG_UPDATE_TS_RECENT;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpSequence.before;

public class Tcp4Multiplexer extends TcpMultiplexer {
    private final SocketChannelFactory socketChannelFactory;
    private final EventLoopGroup childGroup;
    private final int connTimeoutMs;

    public Tcp4Multiplexer(TcpConfig config) {
        this(config, null, null, null);
    }

    public Tcp4Multiplexer(TcpConfig config, DataConsumer dataConsumer) {
        this(config, null, null, dataConsumer);
    }

    public Tcp4Multiplexer(TcpConfig config,
                           SocketChannelFactory socketChannelFactory,
                           EventLoopGroup childGroup,
                           DataConsumer dataConsumer) {
        super(config, dataConsumer);
        this.socketChannelFactory = socketChannelFactory;
        this.childGroup = childGroup;
        this.connTimeoutMs = 5_000;
    }

    @Override
    protected TcpSock init(TcpSock sk) {
        return sk;
    }

    @Override
    public void tcp_rcv(ChannelHandlerContext net, TcpPacketBuf pkt) {
        tcp_v4_rcv(net, pkt);
    }

    @Override
    public void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err) {
        TcpOutput.INSTANCE.tcp_v4_send_reset(net, pkt);
    }

    @Override
    public void inet_rtx_syn_ack(ChannelHandlerContext net, TcpSock listenSock, tcp_request_sock req) {
        req.request().retransmitSynAck(net.channel());
    }

    @Override
    protected tcp_request_sock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt) {
        return tcp_v4_conn_request(net, listenSock, pkt);
    }

    @Override
    protected TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, tcp_request_sock req) {
        return tcp_v4_syn_recv_sock(net, listenSock, pkt, req);
    }

    protected void tcp_v4_rcv(ChannelHandlerContext net, TcpPacketBuf pkt) {
        SockCommon sk = __inet_lookup_skb(pkt);
        if (sk == null) {
            if (!pkt.isRst()) {
                send_reset(net, pkt, -3);
            }
            return;
        }

        if (sk instanceof tcp_request_sock) {
            tcp_request_sock req = (tcp_request_sock) sk;
            TcpSock nsk = tcp_check_req(net, req.listener(), pkt, req);
            if (nsk == null) {
                return;
            }
            moveToEstablished(req, nsk);
            sk = nsk;
        }

        TcpSock sockToUse = (TcpSock) sk;
        try {
            int err = tcp_v4_do_rcv(net, sockToUse, pkt);
            if (err != 0) {
                send_reset(net, pkt, err);
                if (sockToUse != listenSock) {
                    inet_csk_destroy_sock(sockToUse);
                }
            }
        } catch (Throwable cause) {
            if (sockToUse != listenSock) {
                inet_csk_destroy_sock(sockToUse);
            }
            throw cause;
        }
    }

    protected int tcp_v4_do_rcv(ChannelHandlerContext net, TcpSock sk, TcpPacketBuf pkt) {
        return tcp_rcv_state_process(net, sk, pkt);
    }

    protected int tcp_rcv_state_process(ChannelHandlerContext net, TcpSock sk, TcpPacketBuf pkt) {
        switch (sk.state()) {
            case TCP_CLOSED:
                return -1;
            case TCP_LISTEN:
                if (pkt.isAck()) {
                    return -1;
                }
                if (pkt.isRst()) {
                    return 0;
                }
                if (pkt.isSyn() && !pkt.isFin()) {
                    if (sk_acceptq_is_full()) {
                        return -1;
                    }
                    tcp_request_sock req = conn_request(net, sk, pkt);
                    if (req == null) {
                        return -1;
                    }
                    addToHalfQueue(sk, req);
                    startHandshake(net, req, pkt);
                    return 0;
                }
                return 0;
            case TCP_SYN_SENT:
                return -1;
            default:
                break;
        }

        if (!sk.hasConnection()) {
            return -1;
        }

        TcpIncomingPreValidator validator = new TcpIncomingPreValidator(sk, () -> tcp_done(sk));
        if (!validator.validate(net, pkt)) {
            return 0;
        }

        int reason = tcp_ack(sk, pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
            if (reason <= 0) {
            if (sk.state() == TcpConnectionState.TCP_SYN_RECV) {
                return 0;
            }
            if (reason < 0) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(sk, false);
                return 0;
            }
        }

        switch (sk.state()) {
            case TCP_SYN_RECV:
                tcp_try_establish(sk, pkt);
                break;
            case FIN_WAIT_1:
                if (sk.sndUna() == sk.writeSeq()) {
                    sk.state(TcpConnectionState.FIN_WAIT_2);
                    sk.addShutdown(TcpConstants.SEND_SHUTDOWN);
                    scheduleFinWait2Timeout(sk);
                }
                break;
            case CLOSING:
                if (sk.sndUna() == sk.writeSeq()) {
                    tcp_time_wait(net, sk, TcpConnectionState.TIME_WAIT);
                    return 0;
                }
                break;
            case LAST_ACK:
                if (sk.sndUna() == sk.writeSeq()) {
                    tcp_done(sk);
                    return 0;
                }
                break;
            default:
                break;
        }

        switch (sk.state()) {
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                if (!before(pkt.tcpSeq(), sk.rcvNxt())) {
                    break;
                }
            case FIN_WAIT_1:
            case FIN_WAIT_2:
                if (sk.hasShutdown(TcpConstants.RCV_SHUTDOWN) && hasDataBeyondRcvNxt(sk, pkt)) {
                    send_reset(net, pkt, -1);
                    tcp_done(sk);
                    return 0;
                }
                if (pkt.isFin()) {
                    return tcp_fin_state_process(net, sk, pkt);
                }
                return tcp_data_queue(net, sk, pkt);
            case TCP_ESTABLISHED:
                return tcp_data_queue(net, sk, pkt);
            case TIME_WAIT:
                if (pkt.isFin()) {
                    tcp_time_wait(net, sk, TcpConnectionState.TIME_WAIT);
                }
                return tcp_data_queue(net, sk, pkt);
            default:
                return 0;
        }
        return 0;
    }

    protected tcp_request_sock tcp_v4_conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt) {
        TcpHandshaker req = handshakerFactory.newHandshaker(pkt);
        return new tcp_request_sock(FourTuple.of(pkt), listenSock, req);
    }

    protected TcpSock tcp_v4_syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, tcp_request_sock req) {
        TcpSock newsk = init(req.request().buildChildSock(net.channel(), req.childChannel(), pkt));
        newsk.childCloseListener(req.handshakeCloseListener());
        newsk.state(TcpConnectionState.TCP_SYN_RECV);
        return newsk;
    }

    private void tcp_try_establish(TcpSock sk, TcpPacketBuf pkt) {
        if (!sk.hasConnection()) {
            return;
        }
        tcp_init_wl(sk, pkt.tcpSeq());
        sk.state(TcpConnectionState.TCP_ESTABLISHED);
        tcp_init_transfer(sk);
        sk.rcvMss(tcp_initialize_rcv_mss(sk));
        if (sk.hasShutdown(TcpConstants.SEND_SHUTDOWN)) {
            TcpOutput.INSTANCE.tcp_send_ack(sk);
        }
    }

    private int tcp_fin_state_process(ChannelHandlerContext net, TcpSock sk, TcpPacketBuf pkt) {
        if (!sk.hasConnection() || !pkt.isFin()) {
            return 0;
        }
        sk.rcvNxt(sk.rcvNxt() + 1);
        sk.addShutdown(TcpConstants.RCV_SHUTDOWN);
        TcpOutput.INSTANCE.tcp_send_ack(sk);

        switch (sk.state()) {
            case FIN_WAIT_1:
                sk.state(TcpConnectionState.CLOSING);
                return 0;
            case FIN_WAIT_2:
                tcp_time_wait(net, sk, TcpConnectionState.TIME_WAIT);
                return 0;
            case TIME_WAIT:
                tcp_time_wait(net, sk, TcpConnectionState.TIME_WAIT);
                return 0;
            default:
                return tcp_data_queue(net, sk, pkt);
        }
    }

    private void scheduleFinWait2Timeout(TcpSock sk) {
        if (!sk.hasConnection()) {
            return;
        }
        final int tmo = sk.tcpFinTimeMs();
        if (tmo > TcpConstants.TIME_WAIT_MS) {
            TcpTimerScheduler.INSTANCE.scheduleKeepalive(sk, tmo - TcpConstants.TIME_WAIT_MS, () -> onFinWait2Keepalive(sk));
        } else {
            tcp_time_wait(sk, TcpConnectionState.FIN_WAIT_2, tmo);
        }
    }

    private static boolean hasDataBeyondRcvNxt(TcpSock sk, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int endSeq = determineEndSeq(pkt);
        return endSeq != seq && after(endSeq - (pkt.isFin() ? 1 : 0), sk.rcvNxt());
    }

    private void startHandshake(ChannelHandlerContext net, tcp_request_sock req, TcpPacketBuf pkt) {
        if (socketChannelFactory == null || childGroup == null) {
            send_reset(net, pkt, -88);
            inet_csk_destroy_sock(req);
            return;
        }

        final InetSocketAddress target = resolveTarget(pkt);
        req.synPacket((TcpPacketBuf) pkt.retain());
        req.request().synAckFailureAction(() -> inet_csk_destroy_sock(req));
        req.handshakeCloseListener(future -> {
            if (req.synPacket() != null) {
                TcpOutput.INSTANCE.tcp_v4_send_reset(net, req.synPacket());
            }
            inet_csk_destroy_sock(req);
        });

        req.connectFuture(socketChannelFactory.open(target, connTimeoutMs, false, childGroup, new ChannelInboundHandlerAdapter() {
        }).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                req.childChannel(future.channel());
                req.request().sendSynAckAfterBackendConnected(net.channel());
                future.channel().closeFuture().addListener(req.handshakeCloseListener());
            } else {
                if (req.synPacket() != null) {
                    req.request().sendResetAndAbort(net.channel(), req.synPacket());
                }
                inet_csk_destroy_sock(req);
            }
        }));
    }

    private InetSocketAddress resolveTarget(TcpPacketBuf pkt) {
        final InetAddress resolved = pkt.resolvedDstAddr() != null ? pkt.resolvedDstAddr() : pkt.dstAddr();
        final String host = resolved.getHostName();
        if (!resolved.getHostAddress().equals(host)) {
            return InetSocketAddress.createUnresolved(host, pkt.tcpDstPort());
        }
        return new InetSocketAddress(resolved, pkt.tcpDstPort());
    }

    private void onFinWait2Keepalive(TcpSock sk) {
        if (!sk.hasConnection() || sk.state() != TcpConnectionState.FIN_WAIT_2) {
            return;
        }
        if (sk.linger2() >= 0) {
            final int tmo = sk.tcpFinTimeMs() - (int) TcpConstants.TIME_WAIT_MS;
            if (tmo > 0) {
                tcp_time_wait(sk, TcpConnectionState.FIN_WAIT_2, tmo);
                return;
            }
        }
        sk.addShutdown(TcpConstants.RCV_SHUTDOWN);
        TcpOutput.INSTANCE.tcp_send_reset(sk);
        tcp_done(sk);
    }
}
