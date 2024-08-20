package com.github.pangolin.routing.beta.tun.tcp;

import com.google.common.collect.Lists;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class Socket {

    enum State {
        CLOSED,
        LISTEN,
        CLOSING,
        SYN_SENT,
        SYN_RCVD,
        LAST_ACK,
        TIME_WAIT,
        CLOSE_WAIT,
        FIN_WAIT_1,
        FIN_WAIT_2,
        ESTABLISHED;
    }

    private final ChannelHandlerContext ctx;
    private final IpPacket.IpHeader ipHeader;
    private final AtomicReference<State> state = new AtomicReference<>(State.LISTEN);

    public Socket(final ChannelHandlerContext ctx, final IpPacket.IpHeader ipHeader) {
        this.ctx = ctx;
        this.ipHeader = ipHeader;
    }


    public synchronized void receive(final InetAddress srcAddr, final InetAddress dstAddr, final TcpPacket packet) {
        final TcpPacket.TcpHeader header = packet.getHeader();
        final State state = this.state.get();
        if (State.LISTEN.equals(state)) {
            if (header.getSyn() && !header.getAck()) {
                write(ack(header, srcAddr, dstAddr).ack(true).syn(true));
                this.state.compareAndSet(State.LISTEN, State.SYN_RCVD);
            }
        } else if (State.SYN_RCVD.equals(state)) {
            if (header.getAck()) {
                this.state.compareAndSet(State.SYN_RCVD, State.ESTABLISHED);
            } else if (header.getSyn()) {
                // TODO
            }
        } else if (State.ESTABLISHED.equals(state)) {
            if (header.getFin()) {
                write(ack(header, srcAddr, dstAddr).ack(true));
                this.state.compareAndSet(State.ESTABLISHED, State.CLOSE_WAIT);

                write(ack(header, srcAddr, dstAddr).fin(true));
                this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
            } else if (header.getAck() && !header.getSyn()) {
                final byte[] rawData = packet.getPayload().getRawData();
                System.out.println(new String(rawData, StandardCharsets.UTF_8));
                byte[] bytes = "HTTP/1.1 404".getBytes(StandardCharsets.UTF_8);
                write(ack(header, srcAddr, dstAddr).ack(true).payloadBuilder(UnknownPacket.newPacket(bytes, 0, bytes.length).getBuilder()));
            } else if (!header.getAck() && header.getSyn()) {
                // TODO
            }
        } else if (State.CLOSE_WAIT.equals(state)) {
            // 发送数据完毕后发送 FIN
        } else if (State.LAST_ACK.equals(state)) {
            if (header.getAck()) {
                // close.
            }
        }
    }

    protected void write(TcpPacket.Builder packet) {
        ctx.writeAndFlush(Unpooled.wrappedBuffer(ack(ipHeader).payloadBuilder(packet).build().getRawData()));
    }

    private static IpPacket.Builder ack(final IpPacket.Header ipHeader) {
        return new IpV4Packet.Builder()
                .version(IpVersion.IPV4)
                .tos(((IpV4Packet.IpV4Header) ipHeader).getTos())
                .ttl(((IpV4Packet.IpV4Header) ipHeader).getTtl())
                .identification(((IpV4Packet.IpV4Header) ipHeader).getIdentification())
                .fragmentOffset(((IpV4Packet.IpV4Header) ipHeader).getFragmentOffset())
                .srcAddr(((IpV4Packet.IpV4Header) ipHeader).getDstAddr())
                .dstAddr(((IpV4Packet.IpV4Header) ipHeader).getSrcAddr())
                .protocol(IpNumber.TCP)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
    }

    private static TcpPacket.Builder ack(final TcpPacket.TcpHeader header, final InetAddress srcAddr, final InetAddress dstAddr) {
        final int sequence = header.getAcknowledgmentNumber() != 0 ? header.getAcknowledgmentNumber() : header.getSequenceNumber();

//        List<TcpPacket.TcpOption> options = Lists.newArrayList();
//        options.addAll(header.getOptions());

        return new TcpPacket.Builder()
                .srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .srcPort(header.getDstPort())
                .dstPort(header.getSrcPort())
//                .options(options)     // FIXME
                .sequenceNumber(sequence)
                .acknowledgmentNumber(header.getSequenceNumber() + 1)
//                .ack(true)
//                .syn(true)
                .window((short) 65535)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
    }
}