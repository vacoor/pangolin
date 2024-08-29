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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class RawSocket {

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

    public RawSocket(final ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /*-
     *              |<------- TCP recv window ------->|
     *              |            (rcv.wnd)            |
     *  --------------------------------------------------------------------
     * | .. | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |  15  | ...
     *  --------------------------------------------------------------------
     * |  sent and  |                                 | can't receive until |
     * |acknowledged|                                 |    window moves     |
     *              ^                                 ^
     *              |-closes->              <-shrinks-|-opens->
     *          left edge                        right edge
     *          (rcv.nxt)                    (rcv.nxt + rcv.wnd)
     *
     */

    /**
     * Receive - window.
     */
    private int rcvWnd = 65535;

    /**
     * Receive - initialize sequence number.
     */
    private int rcvIsn;

    /**
     * Receive - next sequence number.
     */
    private int rcvNxt;

    /*-
     *              |<------- TCP send window ------->|
     *              |            (snd.wnd)            |
     *              |               |<-Usable window->|
     *  --------------------------------------------------------------------
     * | .. | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |  15  | ...
     *  --------------------------------------------------------------------
     * |  sent and  | sent and not  |    being sent   |   can't send until  |
     * |acknowledged| acknowledged  |                 |     window moves    |
     *              ^               ^                 ^
     *              |-closes->    snd.nxt   <-shrinks-|-opens->
     *          left edge                        right edge
     *          (snd.una)                    (snd.una + snd.wnd)
     *
     * Usable window = snd.una + snd.wnd + snd.nxt
     */

    /**
     * Send - initialize sequence number.
     */
    private int sndIsn;

    /**
     * Send - unacknowledged sequence number.
     */
    private int sndUna;

    /**
     * Send - next sequence number.
     */
    private int sndNxt;

    /**
     *
     */
    private int sndWnd;

    private int sendMss;


    private int incr(final TcpPacket packet) {
        final TcpPacket.TcpHeader h = packet.getHeader();
        if (h.getSyn() || h.getFin()) {
            return 1;
        }
        Packet payload = packet.getPayload();
        return null != payload ? payload.length() : 0;
    }

    private IpPacket.IpHeader ipHeader;

    private void initSession(final TcpPacket.TcpHeader header) {
        sndWnd = header.getWindowAsInt();
        sendMss = 576 - 20 - 20;
        List<TcpPacket.TcpOption> options = header.getOptions();
        for (TcpPacket.TcpOption option : options) {
            final TcpOptionKind kind = option.getKind();
            if (TcpOptionKind.MAXIMUM_SEGMENT_SIZE.equals(kind)) {
                sendMss = ((TcpMaximumSegmentSizeOption) option).getMaxSegSizeAsInt();
            } else if (TcpOptionKind.WINDOW_SCALE.equals(kind)) {
                sndWnd <<= ((TcpWindowScaleOption) option).getShiftCountAsInt();
            }
        }
    }

    private int determineSndWnd(final TcpPacket.TcpHeader header) {
        int sndWnd = header.getWindowAsInt();
        List<TcpPacket.TcpOption> options = header.getOptions();
        for (TcpPacket.TcpOption option : options) {
            final TcpOptionKind kind = option.getKind();
            if (TcpOptionKind.WINDOW_SCALE.equals(kind)) {
                sndWnd <<= ((TcpWindowScaleOption) option).getShiftCountAsInt();
            }
        }
        return sndWnd;
    }

    public synchronized void receive(final TcpPacket packet, final IpPacket.IpHeader ipHeader) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpPacket.TcpHeader header = packet.getHeader();
        final State state = this.state.get();

        log(header, ipHeader, true);

        if (State.LISTEN.equals(state)) {
            if (header.getSyn() && !header.getAck()) {
                initSession(header);
                this.ipHeader = ipHeader;

                rcvIsn = header.getSequenceNumber();
                rcvNxt = rcvIsn;

                sndIsn = header.getSequenceNumber();
                sndNxt = sndIsn;
                sndUna = sndIsn;

                rcvNxt += incr(packet);
                write(ack(header, srcAddr, dstAddr, 0).ack(true).syn(true), ipHeader);

                log.warn("[S] LISTEN -> SYN_RECV");
                this.state.compareAndSet(State.LISTEN, State.SYN_RCVD);
            } else {
                throw new IllegalStateException();
            }
        } else if (State.SYN_RCVD.equals(state)) {
            if (rcvNxt != header.getSequenceNumber()) {
                /*-
                 * tcp_data_queue
                 * https://blog.csdn.net/wuyongmao/article/details/126265842
                 */
                log.warn("[Out-Of-Order], expected: {}, actual: {}", rcvNxt, header.getSequenceNumber());
                if (rcvNxt < header.getSequenceNumber()) {
                    log.warn("[Out-Of-Order] TCP Previous segment not captured, expected: {}, actual: {}", rcvNxt, header.getSequenceNumber());
                } else {
                    if (header.getSequenceNumber() >= rcvNxt + rcvWnd) {
                        log.warn("[Out-Of-Window]");
                    } else {
                        // tcp_data_queue_ofo(sk, skb)
                    }
                }
                return;
            }
            rcvNxt += incr(packet);
            if (header.getAck()) {
                log.warn("[S] SYN_RECV -> ESTABLISHED");

                sndUna = header.getAcknowledgmentNumber();
                sndWnd = determineSndWnd(header);
                // add to queue
                this.state.compareAndSet(State.SYN_RCVD, State.ESTABLISHED);
            } else if (header.getSyn()) {
                // TODO
                System.out.println("!ACK");
            } else {
                System.out.println("!ACK !SYN");
            }
        } else if (State.ESTABLISHED.equals(state)) {
            if (rcvNxt != header.getSequenceNumber()) {
                log.warn("No Ordered, expected: {}, actual: {}", rcvNxt, header.getSequenceNumber());
                return;
            }
            rcvNxt += incr(packet);
            if (header.getAck()) {
                sndUna = header.getAcknowledgmentNumber();
                sndWnd = determineSndWnd(header);
            }
            if (header.getFin()) {
                // ACK
                write(ack(header, srcAddr, dstAddr, 0).ack(true), ipHeader);
                log.warn("[S] ESTABLISHED -> CLOSE_WAIT");
                this.state.compareAndSet(State.ESTABLISHED, State.CLOSE_WAIT);

                write(ack(header, srcAddr, dstAddr, 0).ack(true).fin(true), ipHeader);
                this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
                log.warn("[S] CLOSE_WAIT -> LAST_ACK");
            } else if (header.getAck() && !header.getSyn()) {
                if (header.getRst()) {
                    this.state.compareAndSet(State.ESTABLISHED, State.CLOSED);
                    onClosed();
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
            if (rcvNxt != header.getSequenceNumber()) {
                log.warn("No Ordered, expected: {}, actual: {}", rcvNxt, header.getSequenceNumber());
                return;
            }
            rcvNxt += incr(packet);
            if (header.getAck()) {
                sndUna = header.getAcknowledgmentNumber();
                sndWnd = determineSndWnd(header);
            }
            // 发送数据完毕后发送 FIN
            // WRITE last data
            // write(ack(header, srcAddr, dstAddr, 0).fin(true).sequenceNumber(header.getAcknowledgmentNumber() + 1).acknowledgmentNumber(header.getSequenceNumber() + 1), ipHeader);
//            write(ack(header, srcAddr, dstAddr, 0).fin(true), ipHeader);
//            this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
//            lastSeq = header.getSequenceNumber();
        } else if (State.LAST_ACK.equals(state)) {
            if (rcvNxt != header.getSequenceNumber()) {
                log.warn("No Ordered, expected: {}, actual: {}", rcvNxt, header.getSequenceNumber());
                return;
            }
            rcvNxt += incr(packet);
            if (header.getAck()) {
                // close.
                sndUna = header.getAcknowledgmentNumber();
                sndWnd = determineSndWnd(header);
                log.warn("[S] LAST_ACK -> CLOSED");
                this.state.compareAndSet(State.LAST_ACK, State.CLOSED);
                onClosed();
            }
        }
    }

    /**
     * https://ty-chen.github.io/linux-kernel-tcp-receive/
     */
    private void tcp_data_queue(TcpPacket skb) {
        final TcpPacket.TcpHeader skbh = skb.getHeader();
        final RawSocket tp = this;
        if (skbh.getSequenceNumber() == this.rcvNxt) {
            if (tcp_receive_window(tp) == 0) {
                //
                out_of_window();
                return;
            }

            /* Ok, In sequence. In window. */

        }
    }

    private int tcp_receive_window(RawSocket tp) {
        return 0;
    }

    private void out_of_window() {

    }

    protected void onClosed() {

    }

    private ConcurrentLinkedQueue<TcpPacket.Builder> sndQueue = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }
    });
    private volatile Future<?> delayAckTask;

    protected void write(TcpPacket.Builder packet, IpPacket.IpHeader ipHeader) {
        /*
        packet.sequenceNumber(sndNxt).acknowledgmentNumber(rcvNxt);
        log(packet.build().getHeader(), ipHeader, false);
        sndNxt += incr(packet.build());

//        int avaWnd = rcvWnd - (rcvNxt - rcvUna);
        log.warn("[RCV-WND] {}", rcvWnd);

        ctx.writeAndFlush(new Tun4Packet(Unpooled.wrappedBuffer(ack(ipHeader).payloadBuilder(packet).build().getRawData())));
        */
        if (!sndQueue.offer(packet)) {
            throw new IllegalStateException();
        }
//        if (null == delayAckTask) {
//            delayAckTask = scheduler.schedule(new Runnable() {
//                @Override
//                public void run() {
        write0();
//                    delayAckTask = null;
//                }
//            }, 1000, TimeUnit.MILLISECONDS);
//        }
    }

    private void write0() {
        final int usableWnd = sndUna + sndWnd - sndNxt;
        log.info("USABLE-WND = {}", usableWnd);
        if (usableWnd <= 0) {
            return;
        }

        TcpPacket.Builder prev = null;
        for (TcpPacket.Builder next = sndQueue.poll(); null != next; next = sndQueue.poll()) {
            if (null == prev) {
                prev = next;
            } else {
                final Packet.Builder prevPayload = prev.getPayloadBuilder();
                final Packet.Builder nextPayload = next.getPayloadBuilder();
                if (null == prevPayload) {
                    prev.payloadBuilder(nextPayload);
                } else if (null != nextPayload) {
                    byte[] rawData1 = prevPayload.build().getRawData();
                    byte[] rawData2 = nextPayload.build().getRawData();
                    byte[] bytes = Arrays.copyOfRange(rawData1, 0, rawData1.length + rawData2.length);
                    System.arraycopy(rawData2, 0, bytes, rawData1.length, rawData2.length);
                    prev.payloadBuilder(new UnknownPacket.Builder().rawData(bytes));
                }
            }
        }
        if (null != prev) {
            prev.sequenceNumber(sndNxt);
            prev.acknowledgmentNumber(rcvNxt);
            sndNxt += incr(prev.build());

            log(prev.build().getHeader(), ipHeader, false);
            ctx.writeAndFlush(new Tun4Packet(Unpooled.wrappedBuffer(ack(ipHeader).payloadBuilder(prev).build().getRawData())));
        }
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
//        List<TcpPacket.TcpOption> options = Lists.newArrayList();
//        options.addAll(header.getOptions());

        return new TcpPacket.Builder()
                .srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .srcPort(header.getDstPort())
                .dstPort(header.getSrcPort())
//                .options(options)     // FIXME
//                .ack(true)
//                .syn(true)
                .window((short) 65535)
//                .window((short)1)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
    }

    private static void log(final TcpPacket.TcpHeader tcpHeader, final IpPacket.IpHeader ipHeader, boolean inbound) {
        String type = String.format("[SEQ=%s, ACK=%s] ", tcpHeader.getSequenceNumber(), tcpHeader.getAcknowledgmentNumber());
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
//        type += tcpHeader.getSequenceNumber() + "/" + tcpHeader.getAcknowledgmentNumber();
        if (inbound) {
            log.info("{} - {}:{} -> {}:{}", type, ipHeader.getSrcAddr(), tcpHeader.getSrcPort().valueAsInt(), ipHeader.getDstAddr(), tcpHeader.getDstPort().valueAsInt());
        } else {
            log.info("{} - {}:{} <- {}:{}", type, ipHeader.getDstAddr(), tcpHeader.getDstPort().valueAsInt(), ipHeader.getSrcAddr(), tcpHeader.getSrcPort().valueAsInt());
        }
    }
}