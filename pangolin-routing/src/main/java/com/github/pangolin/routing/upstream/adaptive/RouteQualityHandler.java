package com.github.pangolin.routing.upstream.adaptive;

import com.github.pangolin.routing.support.handler.client.HandshakeFailureEvent;
import com.github.pangolin.routing.support.handler.client.HandshakeSuccessEvent;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Inbound handler placed after the upstream proxy handler in the channel pipeline.
 * Listens for {@link HandshakeSuccessEvent} / {@link HandshakeFailureEvent} fired by
 * {@code AbstractProxyHandler} and updates the corresponding {@link RouteQuality} entry.
 *
 * <p>Instance is created per-connection and must NOT be annotated {@code @Sharable}.
 */
@Slf4j
public class RouteQualityHandler extends ChannelDuplexHandler {
    private final String serverName;
    private final String destination;
    private final RouteQualityTable qualityTable;
    private final long penalty;
    private final int circuitOpenThreshold;
    private final long circuitBaseMs;
    private final long circuitMaxMs;

    private long startTime;
    private RouteQuality quality;
    private boolean failureRecorded = false;
    private boolean handshakeCompleted = false;

    public RouteQualityHandler(final String serverName,
                               final InetSocketAddress destination,
                               final RouteQualityTable qualityTable,
                               final long penalty,
                               final int circuitOpenThreshold,
                               final long circuitBaseMs,
                               final long circuitMaxMs) {
        this.serverName = serverName;
        this.destination = destination.getHostString() + ":" + destination.getPort();
        this.qualityTable = qualityTable;
        this.penalty = penalty;
        this.circuitOpenThreshold = circuitOpenThreshold;
        this.circuitBaseMs = circuitBaseMs;
        this.circuitMaxMs = circuitMaxMs;
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress,
                        final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        startTime = System.currentTimeMillis();
        quality = qualityTable.getOrCreate(serverName, destination);
        promise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture f) {
                if (!f.isSuccess() && !failureRecorded) {
                    // TCP connection to proxy server failed — record as route failure
                    failureRecorded = true;
                    quality.onFailure(penalty, circuitOpenThreshold, circuitBaseMs, circuitMaxMs);
                    log.debug("[ADAPTIVE] [{}] → {} TCP connect failed: {}", serverName, destination, f.cause().getMessage());
                }
            }
        });
        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt == HandshakeSuccessEvent.INSTANCE) {
            handshakeCompleted = true;
            if (quality != null) {
                final long rtt = System.currentTimeMillis() - startTime;
                quality.onSuccess(rtt, circuitOpenThreshold);
                log.debug("[ADAPTIVE] [{}] → {} success rtt={}ms", serverName, destination, rtt);
            }
        } else if (evt == HandshakeFailureEvent.INSTANCE) {
            if (!failureRecorded) {
                failureRecorded = true;
                if (quality != null) {
                    quality.onFailure(penalty, circuitOpenThreshold, circuitBaseMs, circuitMaxMs);
                    log.debug("[ADAPTIVE] [{}] → {} failure penalty={}ms", serverName, destination, penalty);
                }
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        // Safety net: if the channel closes before handshake events are fired
        if (!handshakeCompleted && !failureRecorded && quality != null) {
            failureRecorded = true;
            quality.onFailure(penalty, circuitOpenThreshold, circuitBaseMs, circuitMaxMs);
            log.debug("[ADAPTIVE] [{}] → {} channel inactive before handshake", serverName, destination);
        }
        ctx.fireChannelInactive();
    }
}
