package com.github.pangolin.routing.beta.tun.tcp;

import com.google.common.collect.Lists;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.Tun4Packet;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
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

@Slf4j
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
    private final AtomicReference<State> state = new AtomicReference<>(State.LISTEN);

    public Socket(final ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    private volatile int lastSeq = 0;


    public synchronized void receive(final TcpPacket packet, final IpPacket.IpHeader ipHeader) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpPacket.TcpHeader header = packet.getHeader();
        final State state = this.state.get();
        if (State.LISTEN.equals(state)) {
            if (header.getSyn() && !header.getAck()) {
                write(ack(header, srcAddr, dstAddr).ack(true).syn(true), ipHeader);
                this.state.compareAndSet(State.LISTEN, State.SYN_RCVD);
            } else {
                System.out.println("XXX");
            }
            lastSeq = header.getSequenceNumber();
        } else if (State.SYN_RCVD.equals(state)) {
            if (header.getAck()) {
                Packet payload = packet.getPayload();
                this.state.compareAndSet(State.SYN_RCVD, State.ESTABLISHED);
                System.out.println("ESTABLISHED: " + payload);
            } else if (header.getSyn()) {
                // TODO
                System.out.println("!ACK");
            } else {
                System.out.println("!ACK !SYN");
            }
            lastSeq = header.getSequenceNumber();
        } else if (State.ESTABLISHED.equals(state)) {
            if (header.getFin()) {
                write(ack(header, srcAddr, dstAddr).ack(true), ipHeader);
                this.state.compareAndSet(State.ESTABLISHED, State.CLOSE_WAIT);
                lastSeq = header.getSequenceNumber();

                write(ack(header, srcAddr, dstAddr).fin(true), ipHeader);
                this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
                lastSeq = header.getSequenceNumber();
            } else if (header.getAck() && !header.getSyn()) {
                if (header.getRst()) {
                    System.out.println("[ACK][RST]");
                    lastSeq = header.getSequenceNumber();
                } else {
                    Packet payload = packet.getPayload();
                    if (null != payload) {
                        final byte[] rawData = packet.getPayload().getRawData();
                        System.out.println(new String(rawData, StandardCharsets.UTF_8));
                        byte[] bytes = "HTTP/1.1 404".getBytes(StandardCharsets.UTF_8);
                        if (lastSeq +1 != header.getSequenceNumber() && lastSeq != header.getSequenceNumber()) {
//                            write(ack(header, srcAddr, dstAddr).acknowledgmentNumber(lastSeq + 1).ack(true), ipHeader);
//                            write(ack(header, srcAddr, dstAddr).acknowledgmentNumber(lastSeq + 1).ack(true), ipHeader);
//                            write(ack(header, srcAddr, dstAddr).acknowledgmentNumber(lastSeq + 1).ack(true), ipHeader);
                        } else {
//                            write(ack(header, srcAddr, dstAddr).ack(true).payloadBuilder(UnknownPacket.newPacket(bytes, 0, bytes.length).getBuilder()), ipHeader);
                            lastSeq = header.getSequenceNumber();
                        }
                    } else {
                        System.out.println("EMPTY");
                        lastSeq = header.getSequenceNumber();
                    }
                }
            } else if (!header.getAck() && header.getSyn()) {
                lastSeq = header.getSequenceNumber();
                // TODO
                System.out.println("222");
            } else {
                lastSeq = header.getSequenceNumber();
                System.out.println("333");
            }
        } else if (State.CLOSE_WAIT.equals(state)) {
            // 发送数据完毕后发送 FIN
            System.out.println("X444");
            lastSeq = header.getSequenceNumber();
        } else if (State.LAST_ACK.equals(state)) {
            lastSeq = header.getSequenceNumber();
            if (header.getAck()) {
                // close.
            }
        }
    }

    protected void write(TcpPacket.Builder packet, IpPacket.IpHeader ipHeader) {
        log(packet.build().getHeader(), ipHeader, false);
        ctx.writeAndFlush(new Tun4Packet(Unpooled.wrappedBuffer(ack(ipHeader).payloadBuilder(packet).build().getRawData())));
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
        final int sequence = header.getAcknowledgmentNumber() != 0 ? header.getAcknowledgmentNumber() : 1;

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

    private static void log(final TcpPacket.TcpHeader tcpHeader, final IpPacket.IpHeader ipHeader, boolean inbound) {
        String type = "";
        if (tcpHeader.getUrg()) {
            type += "[URG]";
        }
        if (tcpHeader.getAck()) {
            type += "[ACK]";
        }
        if (tcpHeader.getPsh()) {
            type += "[PSH]";
        }
        if (tcpHeader.getRst()) {
            type += "[RST]";
        }
        if (tcpHeader.getSyn()) {
            type += "[SYN]";
        }
        if (tcpHeader.getFin()) {
            type += "[FIN]";
        }
        type += tcpHeader.getSequenceNumber() + "/" + tcpHeader.getAcknowledgmentNumber();
        if (inbound) {
            log.info("{}:{} - {} -> {}:{}", ipHeader.getSrcAddr(), tcpHeader.getSrcPort().valueAsInt(), type, ipHeader.getDstAddr(), tcpHeader.getDstPort().valueAsInt());
        } else {
            log.info("{}:{} <- {} - {}:{}", ipHeader.getDstAddr(), tcpHeader.getDstPort().valueAsInt(), type, ipHeader.getSrcAddr(), tcpHeader.getSrcPort().valueAsInt());
        }
    }
}