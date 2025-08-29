package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.determineEndSeq;
import static org.pcap4j.packet.IpPacket.IpHeader;
import static org.pcap4j.packet.IpV4Packet.IpV4Header;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
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
public class Tcp4Connection extends TcpConnection<IpV4Packet> {

    public Tcp4Connection(final Channel parent,
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
        tcp_init_sock();
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
            int err = tcp_rcv_state_process(ih, skb);
            if (0 != err) {
                tcp_v4_send_reset(ih.getHeader(), skb, err);
                destroy();
            }
        } catch (final Throwable cause) {
            cause.printStackTrace();
            destroy();
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

    static void tcp_v4_send_reset(final Channel channel, IpV4Header request, TcpPacket skb, int err) {
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

        channel.writeAndFlush(arg.build());
    }

    /**
     *
     * @param ih
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     */
    @Override
    protected boolean conn_request(final IpV4Packet ih, final TcpPacket skb) {
        return super.conn_request(ih, skb);
    }

    @Override
    protected void send_synack(final IpHeader ih, final TcpPacket syn_skb) {
        tcp_v4_send_synack(ih, syn_skb);
    }

    private void tcp_v4_send_synack(final IpHeader iphdr, final TcpPacket syn_skb) {
        final IpV4Header iph = (IpV4Header) iphdr;
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        final TcpPacket.Builder skb = output.tcp_make_synack(this, iph, syn_skb)
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

    @Override
    protected void INDIRECT_CALL_INET(final TcpBuffer skb) {
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

        debug(ipPacket.getHeader(), buf.build(), false);
//        parent.writeAndFlush(ipPacket).syncUninterruptibly();
        parent.writeAndFlush(ipPacket);
        logTrace("[TCP] Write to {}", ipHeader.getSrcAddr());
    }
}
