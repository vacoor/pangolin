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
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpOptionKind;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class TcpConnection {
    private static final byte FIN = 0x0001;
    private static final byte SYN = 0x0002;
    private static final byte RST = 0x0004;
    private static final byte PSH = 0x0008;
    private static final byte ACK = 0x0010;
    private static final byte URG = 0x0020;

    private static final int DEFAULT_MTU = 1500;
    private static final int MINIMUM_MTU = 576;

    private static final int IP_HEADER_SIZE = 20;
    private static final int TCP_HEADER_SIZE = 20;

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

    private final int mtu = MINIMUM_MTU;

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
    private final AtomicReference<State> state = new AtomicReference<>(State.LISTEN);


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


    private final Channel parent;
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;

    public TcpConnection(final Channel parent, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        this.parent = parent;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
    }

    public synchronized void receive(final TcpPacket packet, final IpHeader ipHeader) {
        try {
            receive0(ipHeader, packet);
        } catch (final Throwable cause) {
            exceptionCaught(cause);
        }
    }

    private synchronized void receive0(final IpHeader ipHeader, final TcpPacket tcpPacket) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpHeader tcpHeader = tcpPacket.getHeader();

        debug(ipHeader, tcpPacket, true);

        if (tcpHeader.getRst()) {
            this.state.set(State.CLOSED);
            connectionInactive();
            throw new IllegalStateException("Connection reset");
        }

        final State state = this.state.get();
        if (State.LISTEN.equals(state)) {
            check(tcpHeader, true, false);
            /*-
             * SYN: connection request.
             */
            initialize(tcpPacket, ipHeader);

            final boolean accepted = connectionRequest(new InetSocketAddress(srcAddr, tcpHeader.getSrcPort().valueAsInt()), new InetSocketAddress(dstAddr, tcpHeader.getDstPort().valueAsInt()), tcpHeader);
            if (accepted) {
                this.state.compareAndSet(State.LISTEN, State.SYN_RCVD);

                // connection accept: send SYN-ACK
                write(newPacket(tcpHeader, srcAddr, dstAddr).ack(true).syn(true), true);
            } else {
                /*-
                 * 可以按照未打开端口处理:
                 * 1. 直接忽略
                 * 2. 响应 RST
                 */
                if (this.state.compareAndSet(State.LISTEN, State.CLOSED)) {
                    write(newPacket(tcpHeader, srcAddr, dstAddr).ack(true).rst(true), true);
                    onDestroy();
                }
            }
        } else if (State.SYN_RCVD.equals(state)) {
            /*-
             * ACK: established.
             */
            check(tcpHeader, false, true);
            if (this.state.compareAndSet(State.SYN_RCVD, State.ESTABLISHED)) {
                connectionActive();
                tcpDataQueue(tcpPacket, ipHeader);
            } else {
                throw new IllegalStateException();
            }
        } else if (State.ESTABLISHED.equals(state)) {
            /*-
             * ACK: transmission.
             */
            check(tcpHeader, false, true);
            tcpDataQueue(tcpPacket, ipHeader);
        } else if (State.CLOSE_WAIT.equals(state)) {
            throw new IllegalStateException("CLOSE WAIT");
        } else if (State.LAST_ACK.equals(state)) {
            /*-
             * ACK: closed.
             */
            check(tcpHeader, false, true);
            if (rcvNxt != tcpHeader.getSequenceNumber()) {
                log.warn("[CONNECTION CLOSED] No Ordered, expected: {}, actual: {}", rcvNxt, tcpHeader.getSequenceNumber());
                return;
            }

            /* 意义不大了.
            rcvNxt += determinePacketSize(packet);
            sndUna = header.getAcknowledgmentNumber();
            sndWnd = determineSndWnd(header);
            */
            this.state.compareAndSet(State.LAST_ACK, State.CLOSED);
            connectionInactive();
        }
        // 完善服务器端主动关闭
        /*else if (State.FIN_WAIT_1.equals(state)) {
            check(header, false, true);
            tcpDataQueue(packet, ipHeader);
            if (!header.getFin()) {
               this.state.compareAndSet(State.FIN_WAIT_1, State.FIN_WAIT_2);
            } else {
                this.state.compareAndSet(State.FIN_WAIT_1, State.TIME_WAIT);
                // set timeout 2MSL: TIME_WAIT --> CLOSED
            }
        } else if (State.FIN_WAIT_2.equals(state)) {
            check(header, false, true);
            tcpDataQueue(packet, ipHeader);
            if (header.getFin()) {
                this.state.compareAndSet(State.FIN_WAIT_2, State.TIME_WAIT);
                // set timeout 2MSL: TIME_WAIT --> CLOSED
            }
        } else if (State.TIME_WAIT.equals(state)) {
            throw new IllegalStateException("CLOSE WAIT);
        }*/
    }

    private TcpPacket.TcpHeader check(final TcpPacket.TcpHeader tcpHeader, final boolean syn, final boolean ack) {
        if (syn != tcpHeader.getSyn() || ack != tcpHeader.getAck()) {
            String type = "Unexpected TCP header: ";
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

            type += ", expected: ";
            if (syn) {
                type += "[SYN]";
            }
            if (ack) {
                type += "[ACK]";
            }

            throw new IllegalStateException(type);
        }
        return tcpHeader;
    }

    /**
     * https://github.com/romain-jacotin/quic/blob/master/doc/TCP.md#-segment-arrives
     */
    private void initialize(final TcpPacket syn, final IpPacket.IpHeader ipHeader) {
        final TcpPacket.TcpHeader header = syn.getHeader();

        /*-
         * receive & send Initial Sequence Number.
         */
        rcvIsn = header.getSequenceNumber();
        sndIsn = header.getSequenceNumber();  // XXX: generate it.

        sndUna = sndIsn;
        sndNxt = sndIsn;
        sndWnd = determineSndWnd(header);
        sndMss = determineSndMss(header);
        cwnd = sndMss;

        rcvNxt = rcvIsn;
        rcvNxt += determinePacketSize(syn);

        this.ipHeader = ipHeader;
    }

    private void adjustUnaAndWnd(final TcpPacket packet) {
        final TcpPacket.TcpHeader tcpHeader = packet.getHeader();
        sndUna = tcpHeader.getAcknowledgmentNumber();
        sndWnd = determineSndWnd(tcpHeader);

        rcvNxt += determinePacketSize(packet);

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
            cwnd = cwnd < ssthresh ? cwnd + sndMss : cwnd + sndMss / cwnd;

            Packet payload = packet.getPayload();
            if (null == payload || payload.length() < 1) {
//                    write(newPacket(header, srcAddr, dstAddr, 0).newPacket(true), ipHeader, false);
            } else {
                final byte[] rawData = packet.getPayload().getRawData();
                connectionRead(rawData);
            }

            // FIN 处理
            if (header.getFin()) {
                connectionInactive();
//                debug.warn("[CONNECTION FINISH] {}: CLOSE WAIT", connection);
                // ACK
                log.info("[S] ESTABLISHED -> CLOSE_WAIT");
                if (this.state.compareAndSet(State.ESTABLISHED, State.CLOSE_WAIT)) {
                    // client FIN-ACK
                    write(newPacket(header, srcAddr, dstAddr).ack(true), true);

                    while (flush()) {

                    }

                    // server FIN
                    write(newPacket(header, srcAddr, dstAddr).ack(true).fin(true), true);
                    this.state.compareAndSet(State.CLOSE_WAIT, State.LAST_ACK);
                    log.info("[S] CLOSE_WAIT -> LAST_ACK");
                }
            }

            // CHECK 乱序队列, 是否可以移动到 sk_receive_queue.
        } else if (sequence + size <= rcvNxt) {
            /*-
             * 数据段超出接收窗口左沿(客户端重传), ACK 客户端未正确收到.
             * Out-Of-Window, 立即 ACK.
             */
            log.warn("[TCP Retransmission]");
            write(newPacket(header, srcAddr, dstAddr).ack(true), true);
        } else if (sequence >= rcvNxt + rcvWnd) {
            /*-
             * 数据段超出接收窗口右沿(Out-Of-Window), 比如零窗口探测报文段.
             * Out-Of-Window, 立即 ACK.
             */
            log.warn("[OOW] ");
            write(newPacket(header, srcAddr, dstAddr).ack(true), true);
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

                log.warn("XX");
                connectionRead(bytes);
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
            // write(newPacket(header, srcAddr, dstAddr, 0).newPacket(true).rst(true))
        }
    }

    protected void write(TcpPacket.Builder packet, boolean now) {
        if (!sndQueue.offer(packet)) {
            throw new IllegalStateException();
        }
        if (now) {
            if (null != delayAckTask) {
                delayAckTask.cancel(true);
                delayAckTask = null;
            }
            flush();
        }
        if (null == delayAckTask) {
            delayAckTask = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    flush();
                    delayAckTask = null;
                }
            }, 100, TimeUnit.MILLISECONDS);
        }
    }

    protected synchronized boolean flush() {
        final int usableSndWnd = sndUna + sndWnd - sndNxt;
        final int cwndToUse = sndUna + cwnd - sndNxt;
        final int wndToUse = Math.min(usableSndWnd, cwndToUse);
        log.debug("USABLE-WND = {}, CWND-To-USE = {}, USABLE-CWND = {}", usableSndWnd, cwndToUse, wndToUse);

        TcpPacket.Builder current = sndQueue.poll();
        if (null == current) {
            return false;
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

        debug(ipHeader, current.build(), false);

        final IpV4Packet ipPacket = new IpV4Packet.Builder()
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
                .correctChecksumAtBuild(true)
                .payloadBuilder(current)
                .build();
        parent.writeAndFlush(ipPacket);
        return true;
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
        return (h.getSyn() || h.getFin()) && 0 == size ? 1 : size;
    }


    /**
     * 确定发送最大片段(Max-Segment-Size)大小.
     *
     * @param header TCP header.
     * @return 发送窗口大小
     */
    private int determineSndMss(final TcpPacket.TcpHeader header) {
        int sndMss = mtu - IP_HEADER_SIZE - TCP_HEADER_SIZE;
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

    private static TcpPacket.Builder newPacket(final TcpPacket.TcpHeader header, final InetAddress srcAddr, final InetAddress dstAddr) {
        return new TcpPacket.Builder()
                .srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .srcPort(header.getDstPort())
                .dstPort(header.getSrcPort())
//                .options(options)     // FIXME
//                .newPacket(true)
//                .syn(true)
                .window((short) 65535)
//                .window((short)1)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
    }


    private void debug(final IpHeader ipHeader, final TcpPacket tcpPacket, boolean inbound) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpHeader tcpHeader = tcpPacket.getHeader();
        final String srcHostName = srcAddr.getHostName();
        final String dstHostName = dstAddr.getHostName();
        final int srcPort = tcpHeader.getSrcPort().valueAsInt();
        final int dstPort = tcpHeader.getDstPort().valueAsInt();

        final StringBuilder buff = new StringBuilder()
                .append(srcHostName).append(":").append(srcPort)
                .append(" => ")
                .append(dstHostName).append(":").append(dstPort);

        final int len = buff.length();
        if (tcpHeader.getFin()) {
            buff.append("FIN,");
        }
        if (tcpHeader.getSyn()) {
            buff.append("SYN,");
        }
        if (tcpHeader.getRst()) {
            buff.append("RST,");
        }
        if (tcpHeader.getPsh()) {
            buff.append("PSH,");
        }
        if (tcpHeader.getAck()) {
            buff.append("ACK,");
        }
        if (tcpHeader.getUrg()) {
            buff.append("URG,");
        }

        if (buff.length() > len) {
            buff.replace(buff.length() - 1, buff.length(), "] ").insert(len, " [");
        }

        final boolean useRelative = true;
        int sequence = tcpHeader.getSequenceNumber();
        int acknowledgment = tcpHeader.getAcknowledgmentNumber();
        if (useRelative) {
            final boolean syn = tcpHeader.getSyn();
            if (inbound) {
                sequence -= !syn ? rcvIsn : sequence;
                acknowledgment -= !syn ? sndIsn : acknowledgment - 1;
            } else {
                sequence -= !syn ? sndIsn : sequence;
                acknowledgment -= !syn ? rcvIsn : acknowledgment - 1;
            }
        }

        buff.append("SEQ=").append(sequence)
                .append(" ACK=").append(acknowledgment);

        log.info(buff.toString());
    }


    volatile Channel channel;
    int connTimeoutMs = 30 * 1000;
    EventLoopGroup group = new NioEventLoopGroup();

    private boolean connectionRequest(final InetSocketAddress src, final InetSocketAddress dst, TcpPacket.TcpHeader header) {
        final InetSocketAddress resolved = resolve(dst);
        final ChannelFuture cf = socketChannelFactory.open(resolved, connTimeoutMs, true, group, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
                    final ByteBuf buf = (ByteBuf) msg;
                    final byte[] payload = ByteBufUtil.getBytes(buf);

                    UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, 0, payload.length).getBuilder();
                    write(newPacket(header, src.getAddress(), dst.getAddress()).ack(true).psh(true).payloadBuilder(builder), true);
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }
        });

        try {
            channel = cf.sync().channel();
            channel.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        write(newPacket(header, src.getAddress(), dst.getAddress()).rst(true), true);
                        onDestroy();
                    }
                }
            });
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
        if ("198.18.0.200".equals(host)) {
            return new InetSocketAddress("139.196.84.154", dst.getPort());
        }

        return dst;
    }

    private void connectionActive() {
    }

    private void connectionRead(final byte[] bytes) {
        if (null != channel) {
            channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
        }
    }

    private void connectionInactive() {
        if (null != channel && channel.isActive()) {
            channel.close();
        }
        onDestroy();
    }

    private void exceptionCaught(final Throwable cause) {
        connectionInactive();
    }

    protected void onDestroy() {
    }
}