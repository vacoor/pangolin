package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.SockCommon;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.request_sock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logify;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.*;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c">tcp_ipv4.c</a>
 */
@Slf4j
public class Tcp4Demultiplexer extends TcpDemultiplexer {

    public Tcp4Demultiplexer(
            Map<String, tcp_request_sock> synRegistry,
            Map<String, TcpSock> establishedRegistry,
            final EventLoopGroup childGroup,
            final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        /**
         * https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1741.
         */
        super(synRegistry, establishedRegistry, childGroup, dnsEngine, factory, new request_sock_ops() {

            @Override
            public void send_ack(Channel net, TcpSock sk, TcpPacketBuf pkt, request_sock req) {
                // FIXME
                // tcp_v4_reqsk_send_ack
            }

            @Override
            public void send_reset(Channel net, TcpSock sk, TcpPacketBuf pkt, int reason) {
                tcp_v4_send_reset(net, pkt, reason);
            }

        });
    }

    @Override
    public void tcp_rcv(final Channel net, final TcpPacketBuf pkt) {
        tcp_v4_rcv(net, pkt);
    }

    @Override
    protected TcpSock init(TcpSock sk) {
        tcp_v4_init_sock(sk);
        return sk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2523">tcp_v4_init_sock</a>
     */
    private void tcp_v4_init_sock(final TcpSock sk) {
        tcp_init_sock(sk);
        /*-
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2483">ipv4_specific</a>
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2523">tcp_v4_init_sock</a>
         */
        sk.icsk_af_ops = new inet_connection_sock_af_ops() {
            @Override
            public void send_check(Channel net, TcpSock sk, TcpPacketBuf pkt) {
            }

            @Override
            public tcp_request_sock conn_request(Channel net, TcpSock listenSock, TcpPacketBuf pkt) {
                return tcp_v4_conn_request(net, listenSock, pkt);
            }

            @Override
            public TcpSock syn_recv_sock(Channel net, TcpSock listenSock, TcpPacketBuf pkt, tcp_request_sock req) {
                return tcp_v4_syn_recv_sock(net, listenSock, pkt, req);
            }
        };
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a>
     */
    private void tcp_v4_rcv(final Channel net, final TcpPacketBuf pkt) {
        SockCommon sk = __inet_lookup_skb(pkt);
        if (null == sk) {
            if (!pkt.isRst()) {
                log.warn(logFormat("[TCP] [RCV]", pkt, "NO_TCP_SOCKET(-3)"));
                /*-
                 * FIXME RESET
                 * If the ACK bit is off, sequence number zero is used,
                 *   <SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>
                 * If the ACK bit is on,
                 *   <SEQ=SEG.ACK><CTL=RST>
                 *
                 * @see https://www.rfc-editor.org/rfc/rfc793.txt
                 * @see https://www.rfc-editor.org/rfc/rfc9293.txt
                 */
                send_reset(net, pkt, -3);
            }
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace(logify(pkt, sk instanceof TcpSock ? ((TcpSock) sk).rx_opt.rcv_wscale : 0));
        }

        if (TcpState.TCP_NEW_SYN_RECV.equals(sk.state())) {
            final tcp_request_sock request = (tcp_request_sock) sk;

            final TcpSock nsk = tcp_check_req(net, (TcpSock) request.skc_listener, pkt, request);
            if (nsk == null) {
                // RST or SYN retransmit handled inside tcp_check_req — drop packet
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug(logFormat("[TCP] [HANDSHAKE]", pkt, "Connection handshake 3/3: ACK"));
            }

            // Cancel SYN-ACK retransmission timer only after handshake is confirmed valid
            if (request.rsk_timer != null) {
                timer.sk_stop_timer(request.rsk_timer);
                request.rsk_timer = null;
            }

            nsk.state(TcpState.TCP_SYN_RECV);
            moveToEstablished(request, nsk);

            if (log.isDebugEnabled()) {
                log.info(logFormat("[TCP] [STATE]", pkt, "Connection ESTABLISHED"));
            }
            sk = nsk;
        }

        final TcpSock sockToUse = (TcpSock) sk;
        final Channel childChannel = innerChannel(sk);
        if (null != childChannel && childChannel.isRegistered()) {
            EventLoop loop = childChannel.eventLoop();
            if (loop.inEventLoop()) {
                tcp_v4_do_rcv(net, sockToUse, pkt);
            } else {
                // 否则提交到 event loop 中执行，并同步处理可能的异常
                pkt.retain();
                loop.execute(() -> {
                    try {
                        tcp_v4_do_rcv(net, sockToUse, pkt);
                    } finally {
                        pkt.release();
                    }
                });
            }
        } else {
            tcp_v4_do_rcv(net, sockToUse, pkt);
        }
    }

    protected SockCommon __inet_lookup_skb(final TcpPacketBuf pkt) {
        final String lookupKey = uniqueKey(pkt);
        SockCommon sk = establishedRegistry.get(lookupKey);
        return (null == sk && null == (sk = synRegistry.get(lookupKey))) ? listenSock : sk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1897">tcp_v4_do_rcv</a>
     */
    private void tcp_v4_do_rcv(final Channel net, final TcpSock sock, final TcpPacketBuf pkt) {
        // https://www.cnblogs.com/wanpengcoder/p/11750747.html
        try {
            int err = input.tcp_rcv_state_process(net, sock, pkt);
            if (0 != err) {
                // FIXME RESET
                tcp_v4_send_reset(net, pkt, err);
                if (!TcpState.TCP_LISTEN.equals(sock.state())) {
                    inet_csk_destroy_sock(sock);
                }
            }
        } catch (final Throwable cause) {
            log.error("Failed to process TCP state", cause);
            if (!TcpState.TCP_LISTEN.equals(sock.state())) {
                inet_csk_destroy_sock(sock);
            }
        }
    }

    @Override
    public void send_reset(final Channel net, final TcpPacketBuf pkt, int err) {
        tcp_v4_send_reset(net, pkt, err);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L740
    static void tcp_v4_send_reset(final Channel net, final TcpPacketBuf seg, int err) {
        // FIXME RESET
        log.info("SEND-RST: {}", err);

        final TcpBuffer rst = new TcpBuffer().rst(true);
        /*-
         * Swap the send and the receive.
         */
        rst.srcPort(seg.tcpDstPort());
        rst.dstPort(seg.tcpSrcPort());

        /*-
         * If the ACK bit is off, sequence number zero is used,
         *   <SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>
         * If the ACK bit is on,
         *   <SEQ=SEG.ACK><CTL=RST>
         *
         * @see https://www.rfc-editor.org/rfc/rfc793.txt
         * @see https://www.rfc-editor.org/rfc/rfc9293.txt
         */
        if (seg.isAck()) {
            rst.sequenceNumber(seg.tcpAckNum());
        } else {
            rst.sequenceNumber(0);
            rst.ack(true);
            rst.acknowledgmentNumber(determineEndSeq(seg));
        }

        sendRaw(net, rst, seg.dstAddr(), seg.srcAddr());
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     */
    protected tcp_request_sock tcp_v4_conn_request(Channel net, TcpSock listenSock, final TcpPacketBuf pkt) {
        return TcpHandshaker.tcp_conn_request(
                net, this,
                requestSockOps,
                new tcp_request_sock_ops() {

                    /**
                     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L103">tcp_v4_init_seq</a>
                     * @see <a href="https://github.com/torvalds/linux/blob/master/net/core/secure_seq.c#L136">secure_tcp_seq</a>
                     */
                    @Override
                    public int init_seq(final TcpPacketBuf p) {
                        return secureSeq(
                                p.srcAddrBytes(), p.tcpSrcPort(),
                                p.dstAddrBytes(), p.tcpDstPort()
                        );
                    }

                    @Override
                    public long init_ts_off(TcpPacketBuf p) {
                        return 0;
                    }

                    @Override
                    public void send_synack(Channel net, TcpSock listenSock, tcp_request_sock req, TcpPacketBuf syn) {
                        tcp_v4_send_synack(net, listenSock, req, syn);
                    }

                    @Override
                    public void addToHalfQueue(TcpSock listenSock, tcp_request_sock req) {
                        Tcp4Demultiplexer.super.addToHalfQueue(listenSock, req);
                    }
                },
                listenSock, pkt,
                dnsEngine, socketChannelFactory, connTimeoutMs, childGroup, output
        );
    }

    @Override
    public void inet_rtx_syn_ack(final Channel net, final TcpSock listenSock, final tcp_request_sock req) {
        // tcp_make_synack builds entirely from listenSock + req; the original syn packet is not needed
        final TcpBuffer skb = output.tcp_make_synack(listenSock, req, null);
        skb.srcPort(req.ir_num);
        skb.dstPort(req.ir_rmt_port);

        log.info(logFormat("[TCP] [HANDSHAKE]", req.ir_loc_addr, req.ir_num, req.ir_rmt_addr, req.ir_rmt_port, "SYNACK send starting..."));
        net.writeAndFlush(buildIp4Packet(skb, (Inet4Address) req.ir_loc_addr, (Inet4Address) req.ir_rmt_addr));
    }


    protected void tcp_v4_send_synack(Channel net, TcpSock listenSock, tcp_request_sock req, final TcpPacketBuf syn) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        final TcpBuffer skb = output.tcp_make_synack(listenSock, req, syn);
        skb.srcPort(req.ir_num);
        skb.dstPort(req.ir_rmt_port);

        syn.retain();
        log.info(logFormat("[TCP] [HANDSHAKE]", req.ir_loc_addr, req.ir_num, req.ir_rmt_addr, req.ir_rmt_port, "SYNACK send starting..."));
        net.writeAndFlush(buildIp4Packet(skb, (Inet4Address) req.ir_loc_addr, (Inet4Address) req.ir_rmt_addr))
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        try {
                            if (future.isSuccess()) {
                                log.info(logFormat("[TCP] [HANDSHAKE]", req.ir_loc_addr, req.ir_num, req.ir_rmt_addr, req.ir_rmt_port, "SYNACK send successful"));
                                // Start SYN-ACK retransmission timer only after the initial send succeeds.
                                // Deferring to here (instead of reqsk_queue_hash_req) because backend
                                // connection is async: SYN-ACK is not sent until after backend connects.
                                timer.scheduleReqskTimer(net, listenSock, req);
                            } else {
                                log.warn(logFormat("[TCP] [HANDSHAKE]", req.ir_loc_addr, req.ir_num, req.ir_rmt_addr, req.ir_rmt_port, "SYNACK send failed, sending RST and dropping half-open connection"));
                                // FIXME RESET
                                tcp_v4_send_reset(net, syn, -99);
                                inet_csk_destroy_sock(req);
                            }
                        } finally {
                            syn.release();
                        }
                    }
                });
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpSock tcp_v4_syn_recv_sock(Channel net, TcpSock listenSock, TcpPacketBuf pkt, tcp_request_sock req) {
        TcpSock newsk = tcp_create_openreq_child(net, listenSock, req);

        newsk.icsk_ext_hdr_len = 0;
        output.tcp_sync_mss(newsk, listenSock.dst_mtu());
        newsk.advmss = listenSock.tcp_mss_clamp(newsk, listenSock.dst_metric_advmss());

        input.tcp_initialize_rcv_mss(newsk);
        return newsk;
    }

    protected static void _INDIRECT_CALL_INET(Channel net, TcpSock tp, final TcpBuffer skb) {
//        log.trace(logify(pkt, tp.rx_opt.snd_wscale));
        sendRaw(net, skb, tp.ir_loc_addr, tp.ir_rmt_addr);
    }

    /**
     * Dispatch to sendRaw4 or sendRaw6 based on address type.
     */
    static void sendRaw(final Channel net, final TcpBuffer skb,
                        final InetAddress srcAddr, final InetAddress dstAddr) {
        if (srcAddr instanceof Inet4Address) {
            sendRaw4(net, skb, (Inet4Address) srcAddr, (Inet4Address) dstAddr);
        } else {
            // TODO IPv6
            log.info("sendRaw6 not yet implemented");
        }
    }

    /**
     * Build a raw IPv4+TCP packet from TcpBuffer and write to channel.
     */
    static void sendRaw4(final Channel net, final TcpBuffer skb,
                         final Inet4Address srcAddr, final Inet4Address dstAddr) {
        net.writeAndFlush(buildIp4Packet(skb, srcAddr, dstAddr));
    }

    /**
     * Construct a raw IPv4+TCP {@link ByteBuf} from the given {@link TcpBuffer} and addresses.
     * Computes both the TCP checksum (with IPv4 pseudo-header) and the IPv4 header checksum.
     */
    static ByteBuf buildIp4Packet(final TcpBuffer skb,
                                  final Inet4Address srcAddr, final Inet4Address dstAddr) {
        final byte[] srcIp = srcAddr.getAddress();
        final byte[] dstIp = dstAddr.getAddress();

        final byte[] opts = skb.rawOptions();
        final int optLen = opts != null ? opts.length : 0;
        final int tcpHdrLen = 20 + optLen;
        final int payloadLen = skb.payloadLength();
        final int tcpTotalLen = tcpHdrLen + payloadLen;
        final int ipTotalLen = 20 + tcpTotalLen;

        final ByteBuf buf = Unpooled.buffer(ipTotalLen);

        // ---- IPv4 header (20 bytes) ----
        final int ipHdrStart = buf.writerIndex();
        buf.writeByte(0x45);                        // version=4, IHL=5 (20 bytes)
        buf.writeByte(0);                           // DSCP/ECN
        buf.writeShort(ipTotalLen);                 // total length
        buf.writeShort(0);                          // identification
        buf.writeShort(0x4000);                     // flags=DF, fragment offset=0
        buf.writeByte(64);                          // TTL
        buf.writeByte(0x06);                        // protocol = TCP
        buf.writeShort(0);                          // checksum placeholder
        buf.writeBytes(srcIp);                      // source IP
        buf.writeBytes(dstIp);                      // destination IP

        // ---- TCP header (20 bytes + options) ----
        final int tcpHdrStart = buf.writerIndex();
        buf.writeShort(skb.srcPort());
        buf.writeShort(skb.dstPort());
        buf.writeInt(skb.sequenceNumber());
        buf.writeInt(skb.acknowledgmentNumber());
        buf.writeByte((tcpHdrLen / 4) << 4);        // data offset
        buf.writeByte(buildTcpFlags(skb));
        buf.writeShort(skb.window() & 0xFFFF);
        final int tcpChecksumIdx = buf.writerIndex();
        buf.writeShort(0);                          // checksum placeholder
        buf.writeShort(skb.urgentPointer() & 0xFFFF);

        // ---- TCP options ----
        if (optLen > 0) {
            buf.writeBytes(opts);
        }

        // ---- TCP payload ----
        final ByteBuf payload = skb.rawPayload();
        if (payload != null && payload.isReadable()) {
            buf.writeBytes(payload, payload.readerIndex(), payloadLen);
        }

        // ---- TCP checksum ----
        final int tcpChecksum = computeTcpChecksum(buf, srcIp, dstIp, tcpHdrStart, tcpTotalLen);
        buf.setShort(tcpChecksumIdx, tcpChecksum);

        // ---- IPv4 header checksum ----
        final int ipChecksum = computeIpChecksum(buf, ipHdrStart, 20);
        buf.setShort(ipHdrStart + 10, ipChecksum);

        return buf;
    }

    private static int buildTcpFlags(final TcpBuffer skb) {
        int flags = 0;
        if (skb.fin()) flags |= 0x01;
        if (skb.syn()) flags |= 0x02;
        if (skb.rst()) flags |= 0x04;
        if (skb.psh()) flags |= 0x08;
        if (skb.ack()) flags |= 0x10;
        if (skb.urg()) flags |= 0x20;
        return flags;
    }

    /**
     * Compute TCP checksum using IPv4 pseudo-header.
     */
    private static int computeTcpChecksum(final ByteBuf buf,
                                          final byte[] srcIp, final byte[] dstIp,
                                          final int tcpOffset, final int tcpLength) {
        long sum = 0;
        // pseudo-header: srcIp, dstIp, zero, proto=6, tcpLength
        sum += ((srcIp[0] & 0xFF) << 8) | (srcIp[1] & 0xFF);
        sum += ((srcIp[2] & 0xFF) << 8) | (srcIp[3] & 0xFF);
        sum += ((dstIp[0] & 0xFF) << 8) | (dstIp[1] & 0xFF);
        sum += ((dstIp[2] & 0xFF) << 8) | (dstIp[3] & 0xFF);
        sum += 0x0006;      // zero + protocol
        sum += tcpLength;

        // TCP segment (treat as big-endian 16-bit words)
        for (int i = tcpOffset; i + 1 < tcpOffset + tcpLength; i += 2) {
            sum += buf.getUnsignedShort(i);
        }
        if ((tcpLength & 1) != 0) {
            sum += (buf.getUnsignedByte(tcpOffset + tcpLength - 1)) << 8;
        }

        return foldChecksum(sum);
    }

    /**
     * Compute IPv4 header checksum over {@code length} bytes starting at {@code offset}.
     */
    private static int computeIpChecksum(final ByteBuf buf, final int offset, final int length) {
        long sum = 0;
        for (int i = offset; i + 1 < offset + length; i += 2) {
            sum += buf.getUnsignedShort(i);
        }
        return foldChecksum(sum);
    }

    private static int foldChecksum(long sum) {
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

}
