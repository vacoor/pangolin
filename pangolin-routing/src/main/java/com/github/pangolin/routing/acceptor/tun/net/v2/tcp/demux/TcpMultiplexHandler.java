package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.demux;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;

/**
 * TUN-EventLoop–side TCP packet dispatcher (analogous to {@code Http2MultiplexHandler}).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Look up the per-connection {@link TcpConnectionChannel} by 4-tuple.</li>
 *   <li>On first SYN: create a channel, register it on a Worker EventLoop (consistent hash),
 *       attach the {@code childHandler} initialiser.</li>
 *   <li>On subsequent packets: cross-thread dispatch to the connection's Worker EventLoop.</li>
 * </ul>
 *
 * <p><b>Not {@code @ChannelHandler.Sharable}</b>: holds per-instance registry and worker array.
 *
 * <p><b>Thread model</b>:
 * <ul>
 *   <li>TUN EventLoop: registry reads/writes, channel creation, {@code pkt.retain()}/{@code pkt.release()}</li>
 *   <li>Worker EventLoop: {@code fireChildRead()}, TCP state machine</li>
 * </ul>
 */
public final class TcpMultiplexHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TcpMultiplexHandler.class);

    private final TcpConfig config;
    private final ChannelHandler childHandler;
    private final TcpConnectionRegistry registry = new TcpConnectionRegistry();
    private final EventLoop[] workers;

    /**
     * @param config       shared TCP configuration
     * @param childHandler {@link ChannelInitializer} applied to each new connection channel
     * @param workerGroup  worker threads; each TCP connection is pinned to one worker
     */
    public TcpMultiplexHandler(TcpConfig config,
                               ChannelHandler childHandler,
                               EventLoopGroup workerGroup) {
        this.config = config;
        this.childHandler = childHandler;
        List<EventLoop> list = new ArrayList<>();
        for (EventExecutor e : workerGroup) {
            list.add((EventLoop) e);
        }
        this.workers = list.toArray(new EventLoop[0]);
        if (workers.length == 0) {
            throw new IllegalArgumentException("workerGroup must have at least one EventLoop");
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#tcp_v4_rcv">tcp_v4_rcv</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#tcp_v4_do_rcv">tcp_v4_do_rcv</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_rcv_state_process">tcp_rcv_state_process</a>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof TcpPacketBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TcpPacketBuf pkt = (TcpPacketBuf) msg;
        FourTuple fourTuple = FourTuple.of(pkt);

        /*-
         * XXX: CHECK listen sock in here, but this is an unnecessary operation for proxy scenario.
         */

        TcpConnectionChannel connCh = registry.get(fourTuple);
        if (connCh == null) {
            /*-
             * listen sock in TCP_LISTEN state.
             *   ACK -> CONNECT REST
             *   RST -> DISCARD
             *   SYN-FIN -> DISCARD
             *   SYN -> conn_request
             *   * -> DISCARD
             *
             * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_rcv_state_process">tcp_rcv_state_process</a>
             */
            if (pkt.isAck()) {
                log.info(logFormat("[TCP] [RCV]", pkt, "Connection reset: SKB_DROP_REASON_TCP_FLAGS Invalid TCP flag(LISTEN <- ACK)"));
                sendRst(ctx, pkt);
                pkt.release();
                return;
            }
            if (pkt.isRst()) {
                log.info(logFormat("[TCP] [RCV]", pkt, "Packet discard: Connection reset not required"));
                pkt.release();
                return;
            }
            if (!pkt.isSyn() || pkt.isFin()) {
                log.info(logFormat("[TCP] [RCV]", pkt, "Packet discard: TCP_FLAGS"));
                pkt.release();
                return;
            }

            /*-
             * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_conn_request">tcp_conn_request</a>
             */
            log.debug(logFormat("[TCP] [HANDSHAKE]", pkt, "Connection handshake 1/3: SYN"));

            /*-
             * FIXME check accept queue is full
             */

            // First SYN: select Worker via consistent hash (bit-AND clears sign bit safely).
            // ⚠ Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (still negative in Java);
            //   use (hash & Integer.MAX_VALUE) to guarantee non-negative result.
            EventLoop worker = workers[(fourTuple.hashCode() & Integer.MAX_VALUE) % workers.length];
            connCh = new TcpConnectionChannel(
                    ctx.channel(), fourTuple, worker,
                    () -> registry.remove(fourTuple)   // deregisterCallback — runs on TUN EventLoop
            );
            connCh.pipeline().addLast(childHandler);

            // register() sets eventLoop on the channel (volatile write), then posts register0 to Worker.
            // After this line, connCh.eventLoop() is immediately usable even before register0 runs.
            ChannelFuture regFuture = worker.register(connCh);
            regFuture.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    // Worker shut down or register0 failed — remove from registry on TUN EventLoop
                    // (single-writer constraint). Do NOT access pkt here: by the time this listener
                    // fires asynchronously, pkt's refcount is already at 0 (TUN has released its ref).
                    log.warn("[{}] TcpConnectionChannel registration failed, removing from registry: {}",
                            fourTuple, f.cause() != null ? f.cause().getMessage() : "unknown");
                    ctx.channel().eventLoop().execute(() -> registry.remove(fourTuple));
                }
            });

            registry.put(fourTuple, connCh);
        }

        // Cross-thread dispatch to Worker EventLoop.
        //
        // Reference-count contract:
        //   pkt enters this method with refcount = N (typically 1, from the upstream codec).
        //   retain() gives the Worker lambda one extra reference.
        //   TUN releases its own reference at the bottom (regardless of Worker outcome).
        //   fireChildRead() owns the lambda's reference: it either transfers it to the pipeline
        //   (active path — pipeline handler / TailContext releases) or releases directly
        //   (inactive path). The Worker lambda therefore has NO explicit release.
        //
        // Why no `finally { pkt.release() }` in the lambda:
        //   Pipeline handlers (SimpleChannelInboundHandler auto-release, or TailContext)
        //   release exactly once. A redundant release in the lambda would cause refcount < 0.
        final TcpConnectionChannel ch = connCh;
        pkt.retain();
        try {
            ch.eventLoop().execute(() -> ch.fireChildRead(pkt));
        } catch (RejectedExecutionException e) {
            // Worker already shut down: lambda never runs, release the retain() we added.
            log.warn("[{}] Worker rejected packet dispatch (EventLoop shut down?)", fourTuple);
            pkt.release();
        }
        pkt.release();   // TUN thread releases its own reference
    }

    // ── RST helper ───────────────────────────────────────────────────────────

    /**
     * Send a TCP RST in response to an unroutable packet.
     * Builds a minimal IPv4+TCP RST packet; does not modify or release {@code pkt}.
     */
    private static void sendRst(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.isRst()) return;   // never RST a RST (RFC 9293 §3.5.2)

        byte[] srcIp = pkt.dstAddrBytes();
        byte[] dstIp = pkt.srcAddrBytes();
        int srcPort = pkt.tcpDstPort();
        int dstPort = pkt.tcpSrcPort();

        int seq;
        int ack;
        int flags;
        if (pkt.isAck()) {
            seq = pkt.tcpAckNum();
            ack = 0;
            flags = 0x04;   // RST only
        } else {
            seq = 0;
            ack = pkt.tcpSeq() + pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0);
            flags = 0x14;   // RST + ACK
        }

        ByteBuf buf = buildIp4TcpPacket(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, 0, null);
        ctx.writeAndFlush(buf);
    }

    /**
     * Assemble a raw IPv4+TCP packet with checksum computation.
     * Re-implements the essential logic of {@code Tcp4Multiplexer.buildIp4Packet()}.
     */
    static ByteBuf buildIp4TcpPacket(byte[] srcIp, int srcPort,
                                     byte[] dstIp, int dstPort,
                                     int seq, int ack, int tcpFlags,
                                     int window, byte[] options) {
        int optLen = options != null ? options.length : 0;
        int tcpHdrLen = 20 + optLen;
        int ipTotalLen = 20 + tcpHdrLen;

        ByteBuf buf = Unpooled.buffer(ipTotalLen);

        // IPv4 header
        int ipHdrStart = buf.writerIndex();
        buf.writeByte(0x45);
        buf.writeByte(0);
        buf.writeShort(ipTotalLen);
        buf.writeShort(0);
        buf.writeShort(0x4000);   // DF flag
        buf.writeByte(64);        // TTL
        buf.writeByte(0x06);      // TCP
        int ipCsumIdx = buf.writerIndex();
        buf.writeShort(0);
        buf.writeBytes(srcIp);
        buf.writeBytes(dstIp);

        // TCP header
        int tcpHdrStart = buf.writerIndex();
        buf.writeShort(srcPort);
        buf.writeShort(dstPort);
        buf.writeInt(seq);
        buf.writeInt(ack);
        buf.writeByte((tcpHdrLen / 4) << 4);
        buf.writeByte(tcpFlags);
        buf.writeShort(window);
        int tcpCsumIdx = buf.writerIndex();
        buf.writeShort(0);
        buf.writeShort(0);  // urgent pointer

        if (optLen > 0) {
            buf.writeBytes(options);
        }

        // TCP checksum
        int tcpCsum = computeTcpChecksum(buf, srcIp, dstIp, tcpHdrStart, tcpHdrLen);
        buf.setShort(tcpCsumIdx, tcpCsum);

        // IP checksum
        int ipCsum = computeIpChecksum(buf, ipHdrStart, 20);
        buf.setShort(ipCsumIdx, ipCsum);

        return buf;
    }

    private static int computeTcpChecksum(ByteBuf buf, byte[] srcIp, byte[] dstIp,
                                          int tcpStart, int tcpLen) {
        long sum = 0;
        // Pseudo-header
        sum += ((srcIp[0] & 0xFF) << 8) | (srcIp[1] & 0xFF);
        sum += ((srcIp[2] & 0xFF) << 8) | (srcIp[3] & 0xFF);
        sum += ((dstIp[0] & 0xFF) << 8) | (dstIp[1] & 0xFF);
        sum += ((dstIp[2] & 0xFF) << 8) | (dstIp[3] & 0xFF);
        sum += 6;         // protocol = TCP
        sum += tcpLen;
        // TCP segment
        int i = tcpStart;
        while (i + 1 < tcpStart + tcpLen) {
            sum += buf.getUnsignedShort(i);
            i += 2;
        }
        if (i < tcpStart + tcpLen) {
            sum += (buf.getUnsignedByte(i) << 8);
        }
        while (sum >> 16 != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    private static int computeIpChecksum(ByteBuf buf, int start, int len) {
        long sum = 0;
        for (int i = start; i < start + len; i += 2) {
            sum += buf.getUnsignedShort(i);
        }
        while (sum >> 16 != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }
}
