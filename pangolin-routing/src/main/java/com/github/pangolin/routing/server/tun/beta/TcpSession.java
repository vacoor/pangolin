package com.github.pangolin.routing.server.tun.beta;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.util.SocketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.*;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpOptionKind;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class TcpSession {

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
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;

    public TcpSession(final ChannelHandlerContext ctx, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        this.ctx = ctx;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
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
     * Usable window = snd.una + snd.wnd - snd.nxt
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

    private int sndMss;

    private int cwnd;

    /**
     * slow start threshold.
     */
    private int ssthresh;


    private IpPacket.IpHeader ipHeader;

    public synchronized void receive(final TcpPacket packet, final IpPacket.IpHeader ipHeader) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpPacket.TcpHeader header = packet.getHeader();
        final State state = this.state.get();

        log(header, ipHeader, true);

        final boolean syn = header.getSyn();
        final boolean ack = header.getAck();
        final boolean fin = header.getFin();
        final boolean rst = header.getRst();
        final boolean psh = header.getPsh();
        final boolean urg = header.getUrg();

        if (State.LISTEN.equals(state)) {
            if (syn && !ack) {
                /*-
                 * SYN
                 */
                final boolean initialized = initSession(packet, ipHeader);
                if (initialized) {
                    log.warn("[S] LISTEN -> SYN_RECV");
                    this.state.compareAndSet(State.LISTEN, State.SYN_RCVD);

                    writeNew(ack(header, srcAddr, dstAddr).ack(true).syn(true), true);
                } else {
                    this.state.compareAndSet(State.LISTEN, State.CLOSING);
                    writeNew(ack(header, srcAddr, dstAddr).ack(true).rst(true), true);
                }
            } else {
                // write(ack(header, srcAddr, dstAddr, 0).ack(true).rst(true), ipHeader);
                throw new IllegalStateException();
            }
        } else if (State.SYN_RCVD.equals(state)) {
            if (ack) {
                if (this.state.compareAndSet(State.SYN_RCVD, State.ESTABLISHED)) {
                    log.warn("[S] SYN_RECV -> ESTABLISHED");
                }

                // XXX 应该按序号处理吧?
                sndUna = header.getAcknowledgmentNumber();
                sndWnd = determineSndWnd(header);
                cwnd = cwnd < ssthresh ? cwnd + sndMss : cwnd + sndMss / cwnd;

                onOpened();

                // add to queue
                tcpDataQueue(packet, ipHeader);
            } else if (header.getSyn()) {
                // TODO
                System.err.println("!ACK");
            } else {
                System.err.println("!ACK !SYN");
            }
        } else if (State.ESTABLISHED.equals(state)) {
            tcpDataQueue(packet, ipHeader);
        } else if (State.CLOSE_WAIT.equals(state)) {
            if (rcvNxt != header.getSequenceNumber()) {
                log.warn("[CLOSE_WAIT] No Ordered, expected: {}, actual: {}", rcvNxt, header.getSequenceNumber());
                return;
            }
            rcvNxt += determinePacketSize(packet);
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
                log.warn("[LAST_ACK] No Ordered, expected: {}, actual: {}", rcvNxt, header.getSequenceNumber());
                return;
            }
            rcvNxt += determinePacketSize(packet);
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

    private boolean initSession(final TcpPacket syn, final IpPacket.IpHeader ipHeader) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();

        final TcpPacket.TcpHeader header = syn.getHeader();

        sndWnd = determineSndWnd(header);
        sndMss = determineSndMss(header);

        sndIsn = header.getSequenceNumber();
        sndNxt = sndIsn;
        sndUna = sndIsn;

        cwnd = sndMss;

        this.ipHeader = ipHeader;

        rcvIsn = header.getSequenceNumber();
        rcvNxt = rcvIsn;


        rcvNxt += determinePacketSize(syn);

        log.info("Initialize:\n"
                        + "snd.wnd: {}\n"
                        + "snd.mss: {}\n"
                        + "snd.isn: {}\n"
                        + "snd.nxt: {}\n"
                        + "snd.una: {}\n"
                        + "cwnd: {}\n"
                        + "rcv.isn: {}\n"
                        + "rcv.nxt: {}",
                sndWnd, sndMss, sndIsn, sndNxt, sndUna, cwnd, rcvIsn, rcvNxt);

        // TODO 打开 TCP 连接
        return onOpen(new InetSocketAddress(srcAddr, header.getSrcPort().valueAsInt()), new InetSocketAddress(dstAddr, header.getDstPort().valueAsInt()), header);
    }

    private void adjustUnaAndSndWnd(final TcpPacket.TcpHeader tcpHeader) {
        sndUna = tcpHeader.getAcknowledgmentNumber();
        sndWnd = determineSndWnd(tcpHeader);
        cwnd = cwnd < ssthresh ? cwnd + sndMss : cwnd + sndMss / cwnd;
    }

    private synchronized void tcpDataQueue(final TcpPacket packet, IpPacket.IpHeader ipHeader) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        /*-
         * tcp_data_queue
         * https://www.cnblogs.com/wanpengcoder/p/11752133.html
         * https://blog.51cto.com/key3feng/8728313
         */
        final TcpPacket.TcpHeader header = packet.getHeader();
        final int sequence = header.getSequenceNumber();
        final int size = determinePacketSize(packet);
        if (sequence == rcvNxt) {
            /*-
             * 数据段序号正是期望的序号,
             * 如果窗口=0, Out-Of-Window, 立即 ACK.
             * 如果窗口>0, 拷贝到用户进程或写入 sk_receive_queue.
             */
            rcvNxt += size;
            sndUna = header.getAcknowledgmentNumber();
            sndWnd = determineSndWnd(header);

            if (header.getAck() && !header.getSyn()) {
                Packet payload = packet.getPayload();
                if (null == payload || payload.length() < 1) {
//                    write(ack(header, srcAddr, dstAddr, 0).ack(true), ipHeader, false);
                } else {
                    final byte[] rawData = packet.getPayload().getRawData();
                    // System.out.println(new String(rawData, StandardCharsets.UTF_8));
                    onMessage(rawData);

                    /*
                    String data = ("<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\r\n"
                            + "<html><head>\r\n"
                            + "<title>404 Not Found</title>\r\n"
                            + "</head><body>\r\n"
                            + "<h1>Not Found</h1>\r\n"
                            + "<p>The requested URL /xxid was not found on this server.</p>\r\n"
                            + "</body></html>\r\n");
                    final int len = data.getBytes(StandardCharsets.UTF_8).length;
                    byte[] bytes = ("HTTP/1.1 200 Not Found\r\n"
                            + "Content-Length: " + len + "\r\n"
//                            + "Transfer-Encoding: chunked\r\n"
                            + "Date: Wed, 21 Aug 2024 01:55:25 GMT\n"
                            + "Server: Apache\r\n\r\n" + data
                    ).getBytes(StandardCharsets.UTF_8);

                    UnknownPacket.Builder builder = UnknownPacket.newPacket(bytes, 0, bytes.length).getBuilder();
                    writeNew(ack(header, srcAddr, dstAddr, payload.length()).ack(true).psh(true).payloadBuilder(builder), false);
                    */
//                    write(ack(header, srcAddr, dstAddr, payload.length()).ack(true).psh(true).payloadBuilder(builder), ipHeader, false);
                    // write(ack(header, srcAddr, dstAddr, payload.length()).ack(true).psh(true).payloadBuilder(builder), ipHeader);
                }
            }

            // FIN 处理
            if (header.getFin()) {
                onReceiveFin();
                // ACK
//                write(ack(header, srcAddr, dstAddr, 0).ack(true), ipHeader, false);
                log.warn("[S] ESTABLISHED -> CLOSE_WAIT");
                this.state.compareAndSet(State.ESTABLISHED, State.CLOSE_WAIT);

//                write(ack(header, srcAddr, dstAddr, 0).ack(true).fin(true), ipHeader, false);
                this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
                log.warn("[S] CLOSE_WAIT -> LAST_ACK");
            }

            // CHECK 乱序队列, 是否可以移动到 sk_receive_queue.
        } else if (sequence + size <= rcvNxt) {
            /*-
             * 数据段超出接收窗口左沿(客户端重传), ACK 客户端未正确收到.
             * Out-Of-Window, 立即 ACK.
             */
            System.err.println("[OOW] TCP Retransmission");
//            write(ack(header, srcAddr, dstAddr, 0).ack(true), ipHeader, true);
            writeNew(ack(header, srcAddr, dstAddr).ack(true), true);
        } else if (sequence >= rcvNxt + rcvWnd) {
            /*-
             * 数据段超出接收窗口右沿(Out-Of-Window), 比如零窗口探测报文段.
             * Out-Of-Window, 立即 ACK.
             */
            System.err.println("[OOW] ");
//            write(ack(header, srcAddr, dstAddr, 0).ack(true), ipHeader, true);
            writeNew(ack(header, srcAddr, dstAddr).ack(true), true);
        } else if (sequence < rcvNxt && sequence + size > rcvNxt) {
            /*-
             * 数据段序号在期望日的序号之前, 结束序号在期望序号之后(数据重叠),
             * sequence ~ rcvNxt ACK 客户端未正确收到.
             * 如果窗口=0, Out-Of-Window??
             * 如果窗口>0, 直接放入 sk_receive_queue
             * ... 同期望序号.
             */
            Packet payload = packet.getPayload();
            final byte[] rawBytes = null != payload ? payload.getRawData() : new byte[0];
            // (rcvNxt - sequence, sequence + determinePacketSize - rcvNxt);
            try {
                final byte[] bytes = Arrays.copyOfRange(rawBytes, rcvNxt - sequence, rawBytes.length);

                onMessage(bytes);
                System.err.println("[XX]");
            } catch (RuntimeException ex) {
                throw ex;
            }
            rcvNxt = sequence + size;
        } else {
            /*-
             * 数据段序号在期望的序号之后且在窗口内的乱序数据.
             * 放入乱序队列.
             */
            // FIXME
            log.warn("[OFO]");
            // write(ack(header, srcAddr, dstAddr, 0).ack(true).rst(true))
        }
    }


    protected void onClosed() {
        onReceiveFin();
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

    protected void writeNew(TcpPacket.Builder packet, boolean now) {
        if (!sndQueue.offer(packet)) {
            throw new IllegalStateException();
        }
        if (now) {
            if (null != delayAckTask) {
                delayAckTask.cancel(true);
                delayAckTask = null;
            }
            flushNew();
        }
        if (null == delayAckTask) {
            delayAckTask = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    flushNew();
                    delayAckTask = null;
                }
            }, 100, TimeUnit.MILLISECONDS);
        }
    }

    protected synchronized void flushNew() {
        final int usableSndWnd = sndUna + sndWnd - sndNxt;
        final int cwndToUse = sndUna + cwnd - sndNxt;
        final int wndToUse = Math.min(usableSndWnd, cwndToUse);
        log.info("USABLE-WND = {}, CWND-To-USE = {}, USABLE-CWND = {}", usableSndWnd, cwndToUse, wndToUse);

        TcpPacket.Builder current = sndQueue.poll();
        if (null == current) {
            return;
        }

        byte[] payload = null != current.getPayloadBuilder() ? current.getPayloadBuilder().build().getRawData() : new byte[0];
        for (; ; ) {
            final TcpPacket.Builder next = sndQueue.peek();
            if (null != next) {
                final byte[] np = null != next.getPayloadBuilder() ? next.getPayloadBuilder().build().getRawData() : new byte[0];
                if (payload.length >= wndToUse && np.length > 0) {
                    break;
                }
                if (payload.length + np.length <= wndToUse) {
                    sndQueue.poll();
                    if (np.length > 0) {
                        payload = Arrays.copyOf(payload, payload.length + np.length);
                        System.arraycopy(np, 0, payload, payload.length - np.length, np.length);
                    }
                    TcpPacket cpkg = current.build();
                    TcpPacket npkg = next.build();
                    current.ack(cpkg.getHeader().getAck() || npkg.getHeader().getAck());
                    current.syn(cpkg.getHeader().getSyn() || npkg.getHeader().getSyn());
                    current.psh(cpkg.getHeader().getPsh() || npkg.getHeader().getPsh());
                    current.fin(cpkg.getHeader().getFin() || npkg.getHeader().getFin());
                } else {
                    int size = wndToUse - payload.length;
                    payload = Arrays.copyOf(payload, payload.length + size);
                    System.arraycopy(np, 0, payload, payload.length - size, size);
                    next.payloadBuilder(UnknownPacket.newPacket(np, size, np.length - size).getBuilder());
                }
            } else {
                break;
            }
        }
        current.sequenceNumber(sndNxt)
                .acknowledgmentNumber(rcvNxt)
                .payloadBuilder(payload.length > 0 ? UnknownPacket.newPacket(payload, 0, payload.length).getBuilder() : null);

        sndNxt += determinePacketSize(current.build());

        log(current.build().getHeader(), ipHeader, false);
        ctx.writeAndFlush(ack(ipHeader).payloadBuilder(current).build());
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

    /**
     * 确定 TCP 包的大小, 用于计算序列增长.
     *
     * @param packet TCP packet
     * @return TCP 用于计算序列增长的大小
     */
    private int determinePacketSize(final TcpPacket packet) {
        final TcpPacket.TcpHeader h = packet.getHeader();
        final Packet payload = packet.getPayload();
        final int size = null != payload ? payload.length() : 0;
        if (size > 0) {
            return size;
        }
        if (h.getSyn() || h.getFin()) {
//        if (h.getSyn() || h.getRst()) {
            return 1;
        }
        return size;
    }


    /**
     * 确定发送最大片段(Max-Segment-Size)大小.
     *
     * @param header TCP header.
     * @return 发送窗口大小
     */
    private int determineSndMss(final TcpPacket.TcpHeader header) {
        // - TCP HEADER SIZE - IP HEADER SIZE
        int sndMss = 576 - 20 - 20;
        final List<TcpPacket.TcpOption> options = header.getOptions();
        for (TcpPacket.TcpOption option : options) {
            final TcpOptionKind kind = option.getKind();
            if (TcpOptionKind.MAXIMUM_SEGMENT_SIZE.equals(kind)) {
                sndMss = ((TcpMaximumSegmentSizeOption) option).getMaxSegSizeAsInt();
            }
        }

        return sndMss;
    }

    /**
     * 确定发送窗口大小.
     *
     * @param header TCP header.
     * @return 发送窗口大小
     */
    private int determineSndWnd(final TcpPacket.TcpHeader header) {
        int sndWnd = header.getWindowAsInt();

        final List<TcpPacket.TcpOption> options = header.getOptions();
        for (TcpPacket.TcpOption option : options) {
            final TcpOptionKind kind = option.getKind();
            if (TcpOptionKind.WINDOW_SCALE.equals(kind)) {
                sndWnd <<= ((TcpWindowScaleOption) option).getShiftCountAsInt();
            }
        }

        return sndWnd;
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


    volatile Channel channel;
    int connTimeoutMs = 30 * 1000;
    EventLoopGroup group = new NioEventLoopGroup();

    private boolean onOpen(final InetSocketAddress src, final InetSocketAddress dst, TcpPacket.TcpHeader header) {
        final InetSocketAddress resolved = resolve(dst);
        final ChannelFuture cf = socketChannelFactory.open(resolved, connTimeoutMs, true, group, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
                    final ByteBuf buf = (ByteBuf) msg;
                    byte[] payload = ByteBufUtil.getBytes(buf);
                    System.out.println(System.currentTimeMillis() + ": " + new String(payload, StandardCharsets.UTF_8));

                    UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, 0, payload.length).getBuilder();
                    writeNew(ack(header, src.getAddress(), dst.getAddress()).ack(true).psh(true).payloadBuilder(builder), true);
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }
        });

        try {
            channel = cf.sync().channel();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return true;
    }

    private InetSocketAddress resolve(final InetSocketAddress dst) {
        final String host = dst.getHostString();
        if (null != dnsEngine) {
            final String raw = dnsEngine.resolve(NetUtil.createByteArrayFromIpAddressString(host));
            if (null != raw) {
                return SocketUtils.toSocketAddress(raw, dst.getPort(), false);
            }
        }
        if ("192.168.1.2".equals(host)) {
            return new InetSocketAddress("153.3.238.102", dst.getPort());
        }
        if ("192.168.1.3".equals(host)) {
            return new InetSocketAddress("139.196.84.154", dst.getPort());
        }
        if ("192.168.3.1".equals(host) || "192.168.3.2".equals(host)) {
            return new InetSocketAddress("139.196.84.154", dst.getPort());
        }

        return dst;
    }

    private void onOpened() {
    }

    private void onMessage(final byte[] bytes) {
        if (null != channel) {
            channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
        }
    }

    private void onReceiveFin() {
        if (null != channel && channel.isActive()) {
            channel.close();
        }
    }
}