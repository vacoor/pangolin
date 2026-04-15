package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel;
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
                TcpOutput.INSTANCE.tcp_v4_send_reset(ctx, pkt);
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

}
