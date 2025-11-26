package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;

import java.net.Inet4Address;
import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock.debug;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.*;
import static org.pcap4j.packet.IpPacket.IpHeader;
import static org.pcap4j.packet.IpV4Packet.IpV4Header;

/**
 *
 */
@Slf4j
public class Tcp4Demultiplexer extends TcpDemultiplexer<IpV4Packet> {

    public Tcp4Demultiplexer(
            Map<String, tcp_request_sock> requestMap,
            Map<String, TcpSock> establishedMap,
            final EventLoopGroup childGroup,
            final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(requestMap, establishedMap, childGroup, dnsEngine, factory, new request_sock_ops() {

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
    protected void init() {
        tcp_v4_init_sock();
    }

    private void tcp_v4_init_sock() {
//        tcp_init_sock(this);
        listenSock.state(TcpState.TCP_LISTEN);
    }

    /**
     * @param ipPacket
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a>
     */
    private void tcp_v4_rcv(final Channel net, final IpPacket ipPacket) {
        final TcpPacket tcpPacket = ipPacket.get(TcpPacket.class);
        if (null == tcpPacket) {
            log.debug("TCP packet not found, discard it");
            return;
        }

        SockCommon sk = __inet_lookup_skb(ipPacket);
        if (null == sk) {
            log.debug("NO_TCP_SOCKET");
            send_reset(net, ipPacket.getHeader(), tcpPacket, -99);
            return;
        }

        // tcp_request_sock
        if (TcpState.TCP_NEW_SYN_RECV.equals(sk.state())) {
            final tcp_request_sock request = (tcp_request_sock) sk;
            final TcpSock nsk = tcp_check_req(net, ipPacket, tcpPacket, request);
            nsk.state(TcpState.TCP_SYN_RECV);
            moveToEstablished(request, nsk);
            sk = nsk;
        }

        final TcpSock sockToUse = (TcpSock) sk;
        if (null != sockToUse.child) {
//            log.info("[TCP] {} => {}", sockKey, sock.child);
            innerChannel(sockToUse).eventLoop().execute(() -> tcp_v4_do_rcv(net, sockToUse, (IpV4Packet) ipPacket, tcpPacket));
        } else {
            tcp_v4_do_rcv(net, sockToUse, (IpV4Packet) ipPacket, tcpPacket);
        }
    }

    protected SockCommon __inet_lookup_skb(final IpPacket ipPacket) {
        final IpHeader iph = ipPacket.getHeader();
        final TcpPacket.TcpHeader th = ipPacket.get(TcpPacket.class).getHeader();
        final String lookupKey = uniqueKey(iph, th);
        SockCommon sk = establishedMap.get(lookupKey);
        return (null == sk && null == (sk = requestSockMap.get(lookupKey))) ? listenSock : sk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1897">tcp_v4_do_rcv</a>
     */
    private void tcp_v4_do_rcv(final Channel net, final TcpSock sock, final IpV4Packet ipPacket, TcpPacket tcpPacket) {
        // https://www.cnblogs.com/wanpengcoder/p/11750747.html

        /*
        if (State.TCP_ESTABLISHED.equals(state.get())) {
            tcp_rcv_established(skb);
            return;
        }
        */

        debug(sock, ipPacket.getHeader(), tcpPacket, true);
        try {
            int err = input.tcp_rcv_state_process(net, sock, ipPacket, tcpPacket);
            if (0 != err) {
                tcp_v4_send_reset(net, ipPacket.getHeader(), tcpPacket, err);
                if (!TcpState.TCP_LISTEN.equals(sock.state())) {
                    inet_csk_destroy_sock(sock);
                }
            }
        } catch (final Throwable cause) {
            cause.printStackTrace();
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
        log.warn("SEND-RST: {}", err);
        // FIXME
        // send reset.
        TcpPacket.Builder buf = new TcpPacket.Builder();

        final TcpPacket.TcpHeader th = skb.getHeader();
        /*-
         * Swap the send and the receive.
         */
        buf.srcAddr(rawRequest.getDstAddr())
                .srcPort(th.getDstPort())
                .rst(true);

        if (th.getAck()) {
            buf.sequenceNumber(th.getAcknowledgmentNumber());
        } else {
            buf.sequenceNumber(1);
            buf.acknowledgmentNumber(determineEndSeq(skb));
        }

        buf.dstAddr(rawRequest.getSrcAddr())
                .dstPort(th.getSrcPort())
                .dstAddr(rawRequest.getSrcAddr())
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);

        IpV4Packet.Builder arg = new IpV4Packet.Builder();
        arg.tos(rawRequest.getTos());
        arg.identification(rawRequest.getIdentification());

        arg.version(IpVersion.IPV4)
                .protocol(rawRequest.getProtocol())
                .srcAddr(rawRequest.getDstAddr())
                .dstAddr(rawRequest.getSrcAddr())
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

    /**
     * @param ipPacket
     * @param tcpPacket
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     */
    @Override
    protected tcp_request_sock conn_request(Channel net, TcpSock listenSock, final IpV4Packet ipPacket, final TcpPacket tcpPacket) {
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
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        final TcpPacket.Builder skb = output.tcp_make_synack(listenSock, req, iph, syn_skb)
                .asBuilder()
                .srcAddr(iph.getDstAddr())
                .dstAddr(iph.getSrcAddr())
                .srcPort(syn_skb.getHeader().getDstPort())
                .dstPort(syn_skb.getHeader().getSrcPort());

        final IpV4Packet ipPacket = new IpV4Packet.Builder()
                .version(IpVersion.IPV4)
                .tos(iph.getTos())
                .ttl(iph.getTtl())
                .identification(iph.getIdentification())
                .fragmentOffset(iph.getFragmentOffset())
                .srcAddr(iph.getDstAddr())
                .dstAddr(iph.getSrcAddr())
                .protocol(IpNumber.TCP)

                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)

                .payloadBuilder(skb)
                .build();

        debug(listenSock, ipPacket.getHeader(), skb.build(), false);

        net.writeAndFlush(ipPacket);
    }

    protected static void _INDIRECT_CALL_INET(Channel net, TcpSock tp, final IpHeader ipHeader, final TcpBuffer skb) {
        final IpV4Header ipHdr = (IpV4Header) ipHeader;
        final Inet4Address dstAddr = (Inet4Address) tp.ir_loc_addr;
        final Inet4Address srcAddr = (Inet4Address) tp.ir_rmt_addr;

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

        debug(tp, ipPacket.getHeader(), buf.build(), false);
//        parent.writeAndFlush(ipPacket).syncUninterruptibly();
        net.writeAndFlush(ipPacket);
    }

}
