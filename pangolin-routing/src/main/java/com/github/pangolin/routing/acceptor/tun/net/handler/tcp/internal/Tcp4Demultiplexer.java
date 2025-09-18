package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.secureSeq;
import static org.pcap4j.packet.IpPacket.IpHeader;
import static org.pcap4j.packet.IpV4Packet.IpV4Header;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.TcpSock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;

/**
 */
@Slf4j
public class Tcp4Demultiplexer extends TcpDemultiplexer<IpV4Packet> {

    public Tcp4Demultiplexer(final Channel parent,
                             final EventLoopGroup childGroup,
                             final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(parent, childGroup, dnsEngine, factory);
    }

    @Override
    protected synchronized void tcp_rcv(final IpV4Packet ip, final TcpPacket tcpPacket) {
        tcp_v4_rcv(ip, tcpPacket);
    }

    @Override
    protected void init() {
        tcp_v4_init_sock();
    }

    private void tcp_v4_init_sock() {
        tcp_init_sock(this);
    }

    /**
     *
     * @param ih
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a>
     */
    private void tcp_v4_rcv(final IpV4Packet ih, TcpPacket skb) {
        tcp_v4_do_rcv(ih, skb);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1897">tcp_v4_do_rcv</a>
     */
    private void tcp_v4_do_rcv(final IpV4Packet ih, TcpPacket skb) {
        // https://www.cnblogs.com/wanpengcoder/p/11750747.html

        /*
        if (State.TCP_ESTABLISHED.equals(state.get())) {
            tcp_rcv_established(skb);
            return;
        }
        */

        debug(ih.getHeader(), skb, true);
        try {
            int err = tcp_rcv_state_process(this, ih, skb);
            if (0 != err) {
                tcp_v4_send_reset(ih.getHeader(), skb, err);
                inet_csk_destroy_sock();
            }
        } catch (final Throwable cause) {
            cause.printStackTrace();
            inet_csk_destroy_sock();
        }
    }

    @Override
    protected void send_reset(final IpHeader request, final TcpPacket skb, int err) {
        tcp_v4_send_reset((IpV4Header) request, skb, err);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L740
    void tcp_v4_send_reset(final IpV4Header request, TcpPacket skb, int err) {
        tcp_v4_send_reset(parent, request, skb, err);
    }

    static void tcp_v4_send_reset(final Channel net, IpV4Header request, TcpPacket skb, int err) {
        log.warn("SEND-RST: {}", err);
        // FIXME
        // send reset.
        TcpPacket.Builder buf = new TcpPacket.Builder();

        final TcpPacket.TcpHeader th = skb.getHeader();
        /*-
         * Swap the send and the receive.
         */
//                buf.dstAddr(ipHeader.getSrcAddr())
//                .srcAddr(ipHeader.getDstAddr())
        buf.dstPort(th.getSrcPort())
                .srcPort(th.getDstPort())
                .rst(true);

        if (th.getAck()) {
            buf.sequenceNumber(th.getAcknowledgmentNumber());
        } else {
            buf.sequenceNumber(1);
            buf.acknowledgmentNumber(determineEndSeq(skb));
        }

        buf.dstAddr(request.getSrcAddr())
                .srcAddr(request.getSrcAddr())
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);

        IpV4Packet.Builder arg = new IpV4Packet.Builder();
        arg.tos(request.getTos());
        arg.identification(request.getIdentification());

        arg.version(IpVersion.IPV4)
                .protocol(request.getProtocol())
                .srcAddr(request.getDstAddr())
                .dstAddr(request.getSrcAddr())
                .ttl(request.getTtl())
                // FIXME
                .fragmentOffset(request.getFragmentOffset())
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)
                .payloadBuilder(buf)
                .build();

        net.writeAndFlush(arg.build());
    }

    /**
     * @param ih
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     */
    @Override
    protected tcp_request_sock conn_request(TcpSock p, final IpV4Packet ih, final TcpPacket skb) {
        return TcpHandshaker.tcp_conn_request(
                new tcp_request_sock_ops() {

                    @Override
                    public void send_ack() {

                    }

                    @Override
                    public void send_reset() {

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
                    public void send_synack(TcpSock p, tcp_request_sock req, IpHeader ipHdr, TcpPacket skb) {
                        tcp_v4_send_synack(p, req, ipHdr, skb);
                    }

                    @Override
                    public void destory() {
                        Tcp4Demultiplexer.this.destroy0();
                    }

                    @Override
                    public void INDIRECT_CALL_INET(TcpBuffer buffer) {
                        Tcp4Demultiplexer.this.INDIRECT_CALL_INET(p, buffer);
                    }
                }, p, ih, skb,
                dnsEngine, socketChannelFactory, connTimeoutMs, childGroup, output
        );
    }

    protected void tcp_v4_send_synack(TcpSock p, tcp_request_sock req, final IpHeader iphdr, final TcpPacket syn_skb) {
        final IpV4Header iph = (IpV4Header) iphdr;
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        final TcpPacket.Builder skb = output.tcp_make_synack(p, req, iph, syn_skb)
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

        debug(ipPacket.getHeader(), skb.build(), false);

        parent.writeAndFlush(ipPacket);
    }

    protected void INDIRECT_CALL_INET(TcpSock tp, final TcpBuffer skb) {
        final IpV4Header ipHdr = (IpV4Header) tp.ipHeader;

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

        debug(ipPacket.getHeader(), buf.build(), false);
//        parent.writeAndFlush(ipPacket).syncUninterruptibly();
        parent.writeAndFlush(ipPacket);
    }

    protected void destroy0() {
    }
}
