package com.github.pangolin.routing.beta.tun.tcp;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.Tun4Packet;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpMaximumSegmentSizeOption;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpWindowScaleOption;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpOptionKind;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
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

    private int rcvIsn;
    private int rcvNxt;

    private int sndIsn;
    private int sndNxt;

    private int sendWindow;
    private int sendMss;


    private int incr(final TcpPacket packet) {
        final TcpPacket.TcpHeader h = packet.getHeader();
        if (h.getSyn() || h.getFin()) {
            return 1;
        }
        Packet payload = packet.getPayload();
        return null != payload ? payload.length() : 0;
    }

    private void createSession(final TcpPacket.TcpHeader header) {
        sendWindow = header.getWindowAsInt();
        sendMss = 576 - 20 - 20;
        List<TcpPacket.TcpOption> options = header.getOptions();
        for (TcpPacket.TcpOption option : options) {
            final TcpOptionKind kind = option.getKind();
            if (TcpOptionKind.MAXIMUM_SEGMENT_SIZE.equals(kind)) {
                sendMss = ((TcpMaximumSegmentSizeOption) option).getMaxSegSizeAsInt();
            } else if (TcpOptionKind.WINDOW_SCALE.equals(kind)) {
                sendWindow <<= ((TcpWindowScaleOption) option).getShiftCountAsInt();
            }
        }
    }

    public synchronized void receive(final TcpPacket packet, final IpPacket.IpHeader ipHeader) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpPacket.TcpHeader header = packet.getHeader();
        final State state = this.state.get();
        if (State.LISTEN.equals(state)) {
            if (header.getSyn() && !header.getAck()) {
                createSession(header);

                rcvIsn = header.getSequenceNumber();
                rcvNxt = header.getSequenceNumber();

                sndIsn = header.getSequenceNumber();
                sndNxt = sndIsn;

                rcvNxt += incr(packet);
                write(ack(header, srcAddr, dstAddr, 0).ack(true).syn(true), ipHeader);

                this.state.compareAndSet(State.LISTEN, State.SYN_RCVD);
                log.warn("LISTEN -> SYN_RECV, payload: {}", packet.getPayload());
            } else {
                System.out.println("XXX");
            }
        } else if (State.SYN_RCVD.equals(state)) {
            rcvNxt += incr(packet);
            if (header.getAck()) {
                // add to queue
                this.state.compareAndSet(State.SYN_RCVD, State.ESTABLISHED);
                log.warn("SYN_REVD -> ESTABLISHED, payload: {}", packet.getPayload());
            } else if (header.getSyn()) {
                // TODO
                System.out.println("!ACK");
            } else {
                System.out.println("!ACK !SYN");
            }
        } else if (State.ESTABLISHED.equals(state)) {
            rcvNxt += incr(packet);
            if (header.getFin()) {
                // ACK
                write(ack(header, srcAddr, dstAddr, 0).ack(true), ipHeader);
                this.state.compareAndSet(State.ESTABLISHED, State.CLOSE_WAIT);
                log.warn("ESTABLISHED -> CLOSE_WAIT, payload: {}", packet.getPayload());

                write(ack(header, srcAddr, dstAddr, 0).ack(true).fin(true), ipHeader);
                this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
                log.warn("CLOSE_WAIT -> LAST_ACK, payload: {}", packet.getPayload());
            } else if (header.getAck() && !header.getSyn()) {
                if (header.getRst()) {
                    System.out.println("[ACK][RST]");
                } else {
                    Packet payload = packet.getPayload();
                    if (null == payload) {
                        write(ack(header, srcAddr, dstAddr, 0).ack(true), ipHeader);
                    } else {
                        final byte[] rawData = packet.getPayload().getRawData();
                        System.out.println(new String(rawData, StandardCharsets.UTF_8));

                        String data = ("<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\r\n"
                                + "<html><head>\r\n"
                                + "<title>404 Not Found</title>\r\n"
                                + "</head><body>\r\n"
                                + "<h1>Not Found</h1>\r\n"
                                + "<p>The requested URL /xxid was not found on this server.</p>\r\n"
                                + "</body></html>\r\n");
                        final int len = data.getBytes(StandardCharsets.UTF_8).length;
                        byte[] bytes = ("HTTP/1.1 404 Not Found\r\n"
                                + "Content-Length: " + len + "\r\n"
                                + "Date: Wed, 21 Aug 2024 01:55:25 GMT\n"
                                + "Server: Apache\r\n\r\n" + data
                        ).getBytes(StandardCharsets.UTF_8);

                        UnknownPacket.Builder builder = UnknownPacket.newPacket(bytes, 0, bytes.length).getBuilder();
                        write(ack(header, srcAddr, dstAddr, payload.length()).ack(true).psh(true).payloadBuilder(builder), ipHeader);
                    }
                }
            } else if (!header.getAck() && header.getSyn()) {
                // TODO
                System.out.println("222");
            } else {
                System.out.println("333");
            }
        } else if (State.CLOSE_WAIT.equals(state)) {
            rcvNxt += incr(packet);
            // 发送数据完毕后发送 FIN
            // WRITE last data
            // write(ack(header, srcAddr, dstAddr, 0).fin(true).sequenceNumber(header.getAcknowledgmentNumber() + 1).acknowledgmentNumber(header.getSequenceNumber() + 1), ipHeader);
//            write(ack(header, srcAddr, dstAddr, 0).fin(true), ipHeader);
//            this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
//            lastSeq = header.getSequenceNumber();
        } else if (State.LAST_ACK.equals(state)) {
            rcvNxt += incr(packet);
            if (header.getAck()) {
                // close.
                this.state.compareAndSet(State.LAST_ACK, State.CLOSED);
                log.warn("LAST_ACK -> CLOSED, payload: {}", packet.getPayload());
            }
        }
    }

    protected void write(TcpPacket.Builder packet, IpPacket.IpHeader ipHeader) {
        packet.sequenceNumber(sndNxt).acknowledgmentNumber(rcvNxt);
        log(packet.build().getHeader(), ipHeader, false);
        sndNxt += incr(packet.build());

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

    private static TcpPacket.Builder ack(final TcpPacket.TcpHeader header, final InetAddress srcAddr, final InetAddress dstAddr, final int receivedPayloadLength) {
        final int sequence = header.getAcknowledgmentNumber() != 0 ? header.getAcknowledgmentNumber() : 1;

//        List<TcpPacket.TcpOption> options = Lists.newArrayList();
//        options.addAll(header.getOptions());
        boolean incr = header.getFin() || header.getSyn();

        return new TcpPacket.Builder()
                .srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .srcPort(header.getDstPort())
                .dstPort(header.getSrcPort())
//                .options(options)     // FIXME
                .sequenceNumber(sequence)
//                .acknowledgmentNumber(header.getSequenceNumber() + Math.max(incr ? 1 : 0, receivedPayloadLength))
                .acknowledgmentNumber(header.getSequenceNumber() + Math.max(incr ? 1 : 0, receivedPayloadLength))
//                .ack(true)
//                .syn(true)
                .window((short) 65535)
//                .window((short)1)
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