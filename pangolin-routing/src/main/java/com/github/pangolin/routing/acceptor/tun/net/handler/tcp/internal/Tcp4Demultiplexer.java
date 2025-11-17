package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.TcpSock.debug;
import static org.pcap4j.packet.IpPacket.IpHeader;
import static org.pcap4j.packet.IpV4Packet.IpV4Header;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.SockCommon;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.TcpSock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;

import java.util.Map;

/**
 */
@Slf4j
public class Tcp4Demultiplexer extends TcpDemultiplexer<IpV4Packet> {

    public Tcp4Demultiplexer(
            Map<String, tcp_request_sock> requestMap,
            Map<String, TcpSock> establishedMap,
            final Channel parent,
                             final EventLoopGroup childGroup,
                             final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(requestMap, establishedMap, parent, childGroup, dnsEngine, factory);
    }

    @Override
    public void tcp_rcv(final IpV4Packet ipPacket, final TcpPacket tcpPacket) {
        tcp_v4_rcv(ipPacket, tcpPacket);
    }

    @Override
    protected void init() {
        tcp_v4_init_sock();
    }

    private void tcp_v4_init_sock() {
//        tcp_init_sock(this);
        listenSock.state.set(TcpState.TCP_LISTEN);
    }

    /**
     *
     * @param ipPacket
     * @param tcpPacket
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a>
     */
    private void tcp_v4_rcv(final IpV4Packet ipPacket, TcpPacket tcpPacket) {
        final String sockKey = uniqueKey(ipPacket.getHeader(), tcpPacket.getHeader());

        SockCommon sock = establishedMap.get(sockKey);

        if (null != sock) {
//            log.info("[TCP] {} Lookup from ESTABLISHED => {}", sockKey, sock);
        } else if (null != (sock = requestSockMap.get(sockKey))) {
//            log.info("[TCP] {} Handshake STEP-3 => {}", sockKey, sock);

            final tcp_request_sock request = (tcp_request_sock) sock;
            final TcpSock childSock = tcp_check_req(request, tcpPacket);
            childSock.state.set(TcpState.TCP_SYN_RECV);
            moveToEstablished(request, childSock);
            sock = childSock;

//            log.info("[TCP] {} Handshake successful => {}", sockKey, childSock);
        } else {
            sock = listenSock;
//            log.info("[TCP] {} Handshake STEP-1", sockKey);
        }

        final SockCommon sockToUse = sock;
        if (null != sock.child) {
//            log.info("[TCP] {} => {}", sockKey, sock.child);
            innerChannel(sock).eventLoop().execute(() -> tcp_v4_do_rcv(sockToUse, ipPacket, tcpPacket));
        } else {
            tcp_v4_do_rcv(sockToUse, ipPacket, tcpPacket);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1897">tcp_v4_do_rcv</a>
     */
    private void tcp_v4_do_rcv(final SockCommon sock, final IpV4Packet ipPacket, TcpPacket tcpPacket) {
        // https://www.cnblogs.com/wanpengcoder/p/11750747.html

        /*
        if (State.TCP_ESTABLISHED.equals(state.get())) {
            tcp_rcv_established(skb);
            return;
        }
        */
        final String sockKey = uniqueKey(ipPacket.getHeader(), tcpPacket.getHeader());

        debug(sock,  ipPacket.getHeader(), tcpPacket, true);
        try {
            int err = tcp_rcv_state_process(sock, ipPacket, tcpPacket);
            if (0 != err) {
                destroy0(sockKey);
                tcp_v4_send_reset(ipPacket.getHeader(), tcpPacket, err);
                inet_csk_destroy_sock(sock);
            }
        } catch (final Throwable cause) {
            cause.printStackTrace();
            destroy0(sockKey);
            inet_csk_destroy_sock(sock);
        }
    }

    @Override
    protected void send_reset(final IpHeader ipHeader, final TcpPacket tcpPacket, int err) {
        tcp_v4_send_reset((IpV4Header) ipHeader, tcpPacket, err);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L740
    void tcp_v4_send_reset(final IpV4Header request, TcpPacket skb, int err) {
        tcp_v4_send_reset(net, request, skb, err);
    }

    static void tcp_v4_send_reset(final Channel net, IpV4Header rawRequest, TcpPacket skb, int err) {
        log.warn("SEND-RST: {}", err);
        // FIXME
        // send reset.
        TcpPacket.Builder buf = new TcpPacket.Builder();

        final TcpPacket.TcpHeader th = skb.getHeader();
        /*-
         * Swap the send and the receive.
         */
        buf.dstPort(th.getSrcPort())
                .srcPort(th.getDstPort())
                .rst(true);

        if (th.getAck()) {
            buf.sequenceNumber(th.getAcknowledgmentNumber());
        } else {
            buf.sequenceNumber(1);
            buf.acknowledgmentNumber(determineEndSeq(skb));
        }

        buf.dstAddr(rawRequest.getSrcAddr())
                .srcAddr(rawRequest.getSrcAddr())
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
    protected tcp_request_sock conn_request(TcpSock listenSock, final IpV4Packet ipPacket, final TcpPacket tcpPacket) {
        final String sockKey = uniqueKey(ipPacket.getHeader(), tcpPacket.getHeader());

        return TcpHandshaker.tcp_conn_request(
                new tcp_request_sock_ops() {

                    @Override
                    public void send_ack() {

                    }

                    @Override
                    public void send_reset() {
                        tcp_v4_send_reset(ipPacket.getHeader(), tcpPacket, -1);
                    }
                }, new tcp_request_sock_ipv4_ops() {

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
                    public void send_synack(TcpSock listenSock, tcp_request_sock req, IpHeader ipHdr, TcpPacket skb) {
                        tcp_v4_send_synack(listenSock, req, ipHdr, skb);
                    }

                    @Override
                    public void addToHalfQueue(TcpSock listenSock, tcp_request_sock req) {
                        Tcp4Demultiplexer.this.addToHalfQueue(listenSock, req);
                    }

                    @Override
                    public void destory() {
                        Tcp4Demultiplexer.this.destroy0(sockKey);
                    }

                    @Override
                    public void INDIRECT_CALL_INET(TcpBuffer buffer) {
                        Tcp4Demultiplexer.this.INDIRECT_CALL_INET(listenSock, ipPacket.getHeader(), buffer);
                    }
                }, listenSock, ipPacket, tcpPacket,
                dnsEngine, socketChannelFactory, connTimeoutMs, childGroup, output
        );
    }

    protected void tcp_v4_send_synack(TcpSock listenSock, tcp_request_sock req, final IpHeader iphdr, final TcpPacket syn_skb) {
        final IpV4Header iph = (IpV4Header) iphdr;
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        final TcpPacket.Builder skb = output.tcp_make_synack(listenSock, req, iph, syn_skb)
                .asBuilder()
                .srcAddr(iph.getDstAddr())
                .dstAddr(iph.getSrcAddr())
                .srcPort(syn_skb.getHeader().getDstPort())
                .dstPort(syn_skb.getHeader().getSrcPort())
                ;

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

    protected void INDIRECT_CALL_INET(TcpSock tp, final IpHeader ipHeader, final TcpBuffer skb) {
        final IpV4Header ipHdr = (IpV4Header) ipHeader;

        TcpPacket.Builder buf = skb
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
                .srcAddr(ipHdr.getDstAddr())
                .dstAddr(ipHdr.getSrcAddr())
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

    protected void destroy0(String sockKey) {
            requestSockMap.remove(sockKey);
            establishedMap.remove(sockKey);
    }
}
