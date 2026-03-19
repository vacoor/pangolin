package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logify;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.*;
import static org.pcap4j.packet.IpPacket.IpHeader;
import static org.pcap4j.packet.IpV4Packet.IpV4Header;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c">tcp_ipv4.c</a>
 */
@Slf4j
public class Tcp4Demultiplexer extends TcpDemultiplexer<IpV4Packet> {

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
            public void send_ack(Channel net, TcpSock sk, IpPacket ipPacket, request_sock req) {
                // FIXME
                // tcp_v4_reqsk_send_ack
            }

            @Override
            public void send_reset(Channel net, TcpSock sk, IpPacket ipPacket, TcpPacket tcpPacket, int reason) {
                tcp_v4_send_reset(net, (IpV4Header) ipPacket.getHeader(), tcpPacket, -1);
            }

        });
    }

    @Override
    public void tcp_rcv(final Channel net, final IpV4Packet ipPacket) {
        tcp_v4_rcv(net, ipPacket);
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
        sk.icsk_af_ops = new inet_connection_sock_af_ops<IpV4Packet>() {
            @Override
            public void send_check(Channel net, TcpSock sk, IpV4Packet ipPacket, TcpPacket tcpPacket) {

            }

            @Override
            public tcp_request_sock conn_request(Channel net, TcpSock listenSock, IpV4Packet ipPacket) {
                return tcp_v4_conn_request(net, listenSock, ipPacket, ipPacket.get(TcpPacket.class));
            }

            @Override
            public TcpSock syn_recv_sock(Channel net, TcpSock listenSock, IpV4Packet ipPacket, tcp_request_sock req) {
                return tcp_v4_syn_recv_sock(net, listenSock, ipPacket, req);
            }
        };
    }

    /**
     * @param ipPacket
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a>
     */
    private void tcp_v4_rcv(final Channel net, final IpV4Packet ipPacket) {
        final TcpPacket tcpPacket = ipPacket.get(TcpPacket.class);
        // log.trace(logFormat(ipPacket, "Packet received"), ipPacket.getHeader().getProtocol().name());

        if (null == tcpPacket) {
            log.warn(logFormat(ipPacket, "TCP packet not found, discard it"));
            return;
        }

        // ...

        SockCommon sk = __inet_lookup_skb(ipPacket);
        if (null == sk) {
            log.warn(logFormat(ipPacket, "NO_TCP_SOCKET"));
            send_reset(net, ipPacket.getHeader(), tcpPacket, -99);
            return;
        }

        log.trace(logify(ipPacket, sk instanceof TcpSock ? ((TcpSock) sk).rx_opt.rcv_wscale : 0));

        if (TcpState.TCP_NEW_SYN_RECV.equals(sk.state())) {
            log.debug(logFormat(ipPacket, "Connection handshake 3/3: ACK"));

            final tcp_request_sock request = (tcp_request_sock) sk;
            final TcpSock nsk = tcp_check_req(net, (TcpSock) request.skc_listener, ipPacket, request);

            nsk.state(TcpState.TCP_SYN_RECV);
            moveToEstablished(request, nsk);

            log.info(logFormat(ipPacket, "Connection ESTABLISHED"));
            sk = nsk;
        }

        final TcpSock sockToUse = (TcpSock) sk;
        if (null != sockToUse.child) {
            try {
                final Channel channel = innerChannel(sockToUse);
                // 检查通道是否已经注册到event loop上
                if (channel.eventLoop().inEventLoop()) {
                    // 如果当前已经在event loop中，直接执行
                    tcp_v4_do_rcv(net, sockToUse, ipPacket);
                } else {
                    // 否则提交到event loop中执行
                    channel.eventLoop().execute(() -> tcp_v4_do_rcv(net, sockToUse, ipPacket));
                }
            } catch (IllegalStateException e) {
                // 如果通道没有注册到event loop上，直接在当前线程执行
                tcp_v4_do_rcv(net, sockToUse, ipPacket);
            }
        } else {
            tcp_v4_do_rcv(net, sockToUse, ipPacket);
        }
    }

    protected SockCommon __inet_lookup_skb(final IpPacket ipPacket) {
        final IpHeader iph = ipPacket.getHeader();
        final TcpPacket.TcpHeader th = ipPacket.get(TcpPacket.class).getHeader();
        final String lookupKey = uniqueKey(iph, th);
        SockCommon sk = establishedRegistry.get(lookupKey);
        return (null == sk && null == (sk = synRegistry.get(lookupKey))) ? listenSock : sk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1897">tcp_v4_do_rcv</a>
     */
    private void tcp_v4_do_rcv(final Channel net, final TcpSock sock, final IpV4Packet ipPacket) {
        // https://www.cnblogs.com/wanpengcoder/p/11750747.html

        /*
        if (State.TCP_ESTABLISHED.equals(state.get())) {
            tcp_rcv_established(skb);
            return;
        }
        */

        final TcpPacket tcpPacket = ipPacket.get(TcpPacket.class);
        try {
            int err = input.tcp_rcv_state_process(net, sock, ipPacket);
            if (0 != err) {
                tcp_v4_send_reset(net, ipPacket.getHeader(), tcpPacket, err);
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
    public void send_reset(final Channel net, final IpHeader ipHeader, final TcpPacket tcpPacket, int err) {
        tcp_v4_send_reset(net, (IpV4Header) ipHeader, tcpPacket, err);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L740
    static void tcp_v4_send_reset(final Channel net, IpV4Header rawRequest, TcpPacket skb, int err) {
        final Inet4Address dstAddr = rawRequest.getDstAddr();
        final Inet4Address srcAddr = rawRequest.getSrcAddr();
        log.warn("SEND-RST: {}", err);
        // FIXME
        // send reset.
        final TcpPacket.TcpHeader th = skb.getHeader();

        TcpPacket.Builder buf = new TcpPacket.Builder();
        /*-
         * Swap the send and the receive.
         */
        buf.srcAddr(dstAddr)
                .srcPort(th.getDstPort())
                .rst(true);

        if (th.getAck()) {
            buf.sequenceNumber(th.getAcknowledgmentNumber());
        } else {
            buf.sequenceNumber(1);
            buf.acknowledgmentNumber(determineEndSeq(skb));
        }

        buf.dstAddr(srcAddr)
                .dstPort(th.getSrcPort())
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);

        IpV4Packet.Builder arg = new IpV4Packet.Builder();
        arg.tos(rawRequest.getTos());
        arg.identification(rawRequest.getIdentification());

        arg.version(IpVersion.IPV4)
                .protocol(rawRequest.getProtocol())
                .srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .ttl(rawRequest.getTtl())
                // FIXME
                .fragmentOffset(rawRequest.getFragmentOffset())
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)
                .payloadBuilder(buf)
                .build();

        net.writeAndFlush(arg.build());
    }

//    @Override
//    protected tcp_request_sock conn_request(Channel net, TcpSock listenSock, final IpV4Packet ipPacket, final TcpPacket tcpPacket) {
//        return tcp_v4_conn_request(net, listenSock, ipPacket, tcpPacket);
//    }

    /**
     * @param ipPacket
     * @param tcpPacket
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     */
    protected tcp_request_sock tcp_v4_conn_request(Channel net, TcpSock listenSock, final IpV4Packet ipPacket, final TcpPacket tcpPacket) {
        return TcpHandshaker.tcp_conn_request(
                net, this,
                requestSockOps,
                new tcp_request_sock_ops() {

                    /**
                     * @param ipHdr
                     * @param tcpHdr
                     * @return
                     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L103">tcp_v4_init_seq</a>
                     * @see <a href="https://github.com/torvalds/linux/blob/master/net/core/secure_seq.c#L136">secure_tcp_seq</a>
                     */
                    public int init_seq(final IpHeader ipHdr, final TcpPacket.TcpHeader tcpHdr) {
                        // return tcpHdr.getSequenceNumber();
                        return secureSeq(
                                ipHdr.getSrcAddr().getAddress(), tcpHdr.getSrcPort().value(),
                                ipHdr.getDstAddr().getAddress(), tcpHdr.getDstPort().value()
                        );
                    }

                    @Override
                    public long init_ts_off(TcpPacket skb) {
                        return 0;
                    }

                    @Override
                    public void send_synack(Channel net, TcpSock listenSock, tcp_request_sock req, IpHeader ipHdr, TcpPacket skb) {
                        tcp_v4_send_synack(net, listenSock, req, ipHdr, skb);
                    }

                    @Override
                    public void addToHalfQueue(TcpSock listenSock, tcp_request_sock req) {
                        Tcp4Demultiplexer.super.addToHalfQueue(listenSock, req);
                    }

//                    @Override
//                    public void INDIRECT_CALL_INET(TcpBuffer buffer) {
//                        _INDIRECT_CALL_INET(net, listenSock, ipPacket.getHeader(), buffer);
//                    }
                }
                , listenSock, ipPacket, tcpPacket,
                dnsEngine, socketChannelFactory, connTimeoutMs, childGroup, output
        );
    }

    protected void tcp_v4_send_synack(Channel net, TcpSock listenSock, tcp_request_sock req, final IpHeader iphdr, final TcpPacket syn_skb) {
        final IpV4Header iph = (IpV4Header) iphdr;
        Inet4Address ir_loc_addr = (Inet4Address) req.ir_loc_addr;
        Inet4Address ir_rmt_addr = (Inet4Address) req.ir_rmt_addr;



        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        final TcpPacket.Builder skb = output.tcp_make_synack(listenSock, req, iph, syn_skb)
                .asBuilder()
                .srcAddr(ir_loc_addr)
                .dstAddr(ir_rmt_addr)
                .srcPort(syn_skb.getHeader().getDstPort())
                .dstPort(syn_skb.getHeader().getSrcPort());

        final IpV4Packet ipPacket = new IpV4Packet.Builder()
                .version(IpVersion.IPV4)
                .tos(iph.getTos())
                .ttl(iph.getTtl())
                .identification(iph.getIdentification())
                .fragmentOffset(iph.getFragmentOffset())
                .srcAddr(ir_loc_addr)
                .dstAddr(ir_rmt_addr)
                .protocol(IpNumber.TCP)

                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)

                .payloadBuilder(skb)
                .build();

        log.warn(logFormat(ipPacket, "SYNACK send starting..."));
        net.writeAndFlush(ipPacket).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                log.warn(logFormat(ipPacket, "SYNACK send successful"));
            }
        });
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpSock tcp_v4_syn_recv_sock(Channel net, TcpSock listenSock, IpV4Packet ipPacket, tcp_request_sock req) {
        final TcpPacket skb = ipPacket.get(TcpPacket.class);
        TcpSock parent = listenSock;
        TcpSock newsk = tcp_create_openreq_child(net, parent, req, skb);

        newsk.icsk_ext_hdr_len = 0;
        output.tcp_sync_mss(newsk, parent.dst_mtu());
        newsk.advmss = parent.tcp_mss_clamp(newsk, parent.dst_metric_advmss());

        input.tcp_initialize_rcv_mss(newsk);
        return newsk;
    }


    protected static void _INDIRECT_CALL_INET(Channel net, TcpSock tp, final IpHeader ipHeader, final TcpBuffer skb) {
        final IpV4Header ipHdr = (IpV4Header) ipHeader;
        final Inet4Address dstAddr = (Inet4Address) tp.ir_loc_addr;
        final Inet4Address srcAddr = (Inet4Address) tp.ir_rmt_addr;

        // Zero-copy path: when the TcpBuffer was built from a ByteBuf slice (upstream data),
        // assemble IP+TCP headers manually and combine with the payload via CompositeByteBuf —
        // bypassing all pcap4j serialization for the data portion.
        final ByteBuf rawPayload = skb.rawPayload();
        if (rawPayload != null && (skb.options() == null || skb.options().isEmpty())) {
            skb.srcAddr(dstAddr).dstAddr(srcAddr);
            sendDirect(net, skb, srcAddr, dstAddr, rawPayload);
            return;
        }

        // pcap4j path (SYN / SYN-ACK / FIN / ACK-only / retransmit with options)
        TcpPacket.Builder buf = skb
                .srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .asBuilder()
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);

        final IpV4Packet ipPacket = new IpV4Packet.Builder()
                .version(IpVersion.IPV4)
                .tos(ipHdr.getTos())
                .ttl(ipHdr.getTtl())
                .identification(ipHdr.getIdentification())
                .fragmentOffset(ipHdr.getFragmentOffset())

                .srcAddr(dstAddr)
                .dstAddr(srcAddr)

                .protocol(IpNumber.TCP)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)
                .payloadBuilder(buf)
                .build();

        log.trace(logify(ipPacket, tp.rx_opt.snd_wscale));
        net.writeAndFlush(ipPacket);
    }

    /**
     * Assemble a raw IPv4+TCP packet directly into a {@link io.netty.buffer.CompositeByteBuf},
     * bypassing pcap4j serialization entirely.
     *
     * <p>Layout: {@code [20-byte IPv4 header][20-byte TCP header][payload]}
     *
     * <p>The method retains a slice of {@code payload} for the composite buffer ownership;
     * the caller's reference to {@code rawPayload} is left intact for possible retransmission.
     */
    private static void sendDirect(Channel net, TcpBuffer skb,
                                   Inet4Address srcAddr, Inet4Address dstAddr,
                                   ByteBuf payload) {
        final int payloadLen = payload.readableBytes();

        // --- Allocate 40-byte header buffer (20 IP + 20 TCP) ---
        final ByteBuf headers = net.alloc().buffer(40, 40);

        // === IPv4 header (20 bytes, offset 0) ===
        final byte[] srcBytes = srcAddr.getAddress();
        final byte[] dstBytes = dstAddr.getAddress();
        headers.writeByte(0x45);                        // version=4, IHL=5 (no options)
        headers.writeByte(0x00);                        // DSCP/ECN = 0
        headers.writeShort(40 + payloadLen);            // total length
        headers.writeShort(0x0000);                     // identification (irrelevant with DF=1)
        headers.writeShort(0x4000);                     // flags=DF, fragment offset=0
        headers.writeByte((byte) 64);                   // TTL
        headers.writeByte((byte) 0x06);                 // protocol = TCP
        headers.writeShort(0);                          // IP checksum placeholder  (offset 10)
        headers.writeBytes(srcBytes);                   // source IP
        headers.writeBytes(dstBytes);                   // destination IP

        // === TCP header (20 bytes, offset 20) ===
        headers.writeShort(skb.srcPort().valueAsInt()); // source port
        headers.writeShort(skb.dstPort().valueAsInt()); // destination port
        headers.writeInt(skb.sequenceNumber());         // sequence number
        headers.writeInt(skb.acknowledgmentNumber());   // acknowledgment number
        headers.writeByte(0x50);                        // data offset=5 (20 bytes), reserved=0
        int flags = 0;
        if (skb.urg()) flags |= 0x20;
        if (skb.ack()) flags |= 0x10;
        if (skb.psh()) flags |= 0x08;
        if (skb.rst()) flags |= 0x04;
        if (skb.syn()) flags |= 0x02;
        if (skb.fin()) flags |= 0x01;
        headers.writeByte(flags);
        headers.writeShort(skb.window() & 0xFFFF);     // window size
        headers.writeShort(0);                          // TCP checksum placeholder (offset 36)
        headers.writeShort(0);                          // urgent pointer

        // === TCP checksum (pseudo-header + TCP header + payload) ===
        long sum = 0;
        // pseudo-header: srcIP + dstIP + 0x00 + proto(6) + TCP-segment-length
        sum += ((srcBytes[0] & 0xFF) << 8) | (srcBytes[1] & 0xFF);
        sum += ((srcBytes[2] & 0xFF) << 8) | (srcBytes[3] & 0xFF);
        sum += ((dstBytes[0] & 0xFF) << 8) | (dstBytes[1] & 0xFF);
        sum += ((dstBytes[2] & 0xFF) << 8) | (dstBytes[3] & 0xFF);
        sum += 0x0006;                                  // protocol = TCP
        sum += 20 + payloadLen;                         // TCP segment length
        // TCP header (20 bytes, checksum field already 0)
        for (int i = 20; i < 40; i += 2) {
            sum += ((headers.getByte(i) & 0xFF) << 8) | (headers.getByte(i + 1) & 0xFF);
        }
        // TCP payload
        final int ri = payload.readerIndex();
        for (int i = 0; i < payloadLen - 1; i += 2) {
            sum += ((payload.getByte(ri + i) & 0xFF) << 8) | (payload.getByte(ri + i + 1) & 0xFF);
        }
        if ((payloadLen & 1) != 0) {
            sum += (payload.getByte(ri + payloadLen - 1) & 0xFF) << 8; // odd-byte padding
        }
        // fold carries and invert
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        headers.setShort(36, (int) (~sum & 0xFFFF));

        // === IP header checksum (over 20-byte IP header) ===
        sum = 0;
        for (int i = 0; i < 20; i += 2) {
            sum += ((headers.getByte(i) & 0xFF) << 8) | (headers.getByte(i + 1) & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        headers.setShort(10, (int) (~sum & 0xFFFF));

        // === Assemble CompositeByteBuf: headers + payload (zero-copy) ===
        // retainedSlice increments payload's refCnt; composite owns the new reference.
        // The original rawPayload in TcpBuffer retains its own ref for retransmission.
        final io.netty.buffer.CompositeByteBuf packet = net.alloc().compositeBuffer(2);
        packet.addComponent(true, headers);
        packet.addComponent(true, payload.retainedSlice());

        // Writing a ByteBuf (not IpPacket) bypasses IpPacketCodec.encode and goes
        // directly to the TUN channel write handler.
        net.writeAndFlush(packet);
    }

}