package com.github.pangolin.server;

import com.google.common.base.Preconditions;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;

/**
 *
 */
@Slf4j
public class Discover {
    private static final String AGENT_NAME = "X-Node-Name";
    private static final String AGENT_VERSION = "X-Node-Version";
    private static final String AGENT_INTRANET = "X-Node-Intranet";
    private static final String BACKHAUL_ID = "id";

    /**
     * 已注册的broker节点(id:agent).
     */
    private final ConcurrentMap<String, Agent> registeredAgents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Tunnel> tunnelMap = new ConcurrentHashMap<>();

    boolean agentRegistered(final HandshakeComplete handshake, final ChannelHandlerContext ctx) {
        final Agent agent = createAgent(handshake, ctx);
        if (null == registeredAgents.putIfAbsent(agent.id, agent)) {
            log.info("Agent registered: {}", stringify(agent));
            ctx.channel().closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    log.info("Agent connection closed: {}", stringify(agent));
                    agentUnregistered(agent);
                }
            });
            return true;
        }
        return false;
    }

    private void agentUnregistered(final Agent agent) {
        if (registeredAgents.remove(agent.id, agent)) {
            log.info("Agent unregistered: {}", stringify(agent));
        } else {

        }
    }

    Promise<ChannelHandlerContext> tunnelRequested(final String id, final String agentKey, final URI target, final ChannelHandlerContext accessCtx) {
        final Agent agent = registeredAgents.get(agentKey);
        Preconditions.checkState(null != agent, "Connection unavailable");

        final Promise<ChannelHandlerContext> backhaulPromise = accessCtx.executor().newPromise();
        final Tunnel tunnel = new Tunnel(id, agent, target, accessCtx, backhaulPromise);
        Preconditions.checkState(null == tunnelMap.putIfAbsent(id, tunnel), "The channel id '%s' is already used", id);

        log.info("Tunnel [{} = {}/{} => {}] Connecting", stringify(accessCtx.channel().remoteAddress()), agent.extranet, agent.intranet, target);

        accessCtx.channel().config().setAutoRead(false);
        accessCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try {
                    log.info("Tunnel [{}(!) = {}/{} => {}] Connection closed", stringify(accessCtx.channel().remoteAddress()), agent.extranet, agent.intranet, target);
                    if (!backhaulPromise.isDone()) {
                        backhaulPromise.tryFailure(new IOException("Connection abort"));
                    } else if (backhaulPromise.isSuccess()) {
                        backhaulPromise.getNow().channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                } finally {
                    tunnelMap.remove(id, tunnel);
                }
            }
        });

        final long waitTimeoutMs = 10 * 1000;
        final ScheduledFuture<?> timeoutFuture = accessCtx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                if (!backhaulPromise.isDone()) {
                    log.info("Tunnel [{}(!) = {}/{} => {}] Connection timeout", stringify(accessCtx.channel().remoteAddress()), agent.extranet, agent.intranet, target);
                    backhaulPromise.tryFailure(new ConnectTimeoutException());
                }
            }
        }, waitTimeoutMs, TimeUnit.MILLISECONDS);

        backhaulPromise.addListener(new GenericFutureListener<Future<? super ChannelHandlerContext>>() {
            @Override
            public void operationComplete(Future<? super ChannelHandlerContext> future) throws Exception {
                if (!timeoutFuture.isDone()) {
                    timeoutFuture.cancel(false);
                }
            }
        });

        final String command = id + "->" + target;
        agent.bus.writeAndFlush(new TextWebSocketFrame(command));
        return backhaulPromise;
    }

    void tunnelResponded(final HandshakeComplete handshake, final ChannelHandlerContext backhaulCtx) {
        final QueryStringDecoder decoder = new QueryStringDecoder(handshake.requestUri());
        final List<String> ids = decoder.parameters().get(BACKHAUL_ID);
        final String id = null != ids && !ids.isEmpty() ? ids.get(ids.size() - 1) : null;
        if (null == id) {
            backhaulCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        final Tunnel tunnel = tunnelMap.get(id);
        backhaulCtx.channel().config().setAutoRead(false);
        if (null != tunnel) {
            final Agent agent = tunnel.agent;
            final ChannelHandlerContext accessCtx = tunnel.accessCtx;
            if (!tunnel.backhaulCtxPromise.isDone()) {
                log.info("Tunnel [{}(!) = {}/{} => {}] Connection established", stringify(accessCtx.channel().remoteAddress()), agent.extranet, agent.intranet, tunnel.target);
                tunnel.backhaulCtxPromise.setSuccess(backhaulCtx);
            } else {
                log.warn("Tunnel [{}(!) = {}/{} => {}] Connection already established", stringify(accessCtx.channel().remoteAddress()), agent.extranet, agent.intranet, tunnel.target);
                backhaulCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        } else {
            backhaulCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private Agent createAgent(final HandshakeComplete handshake, final ChannelHandlerContext ctx) {
        final HttpHeaders headers = handshake.requestHeaders();
        final String name = headers.getAsString(AGENT_NAME);
        final String version = headers.getAsString(AGENT_VERSION);
        final String intranet = headers.getAsString(AGENT_INTRANET);
        final String extranet = stringify(ctx.channel().remoteAddress());

        final String id = String.format("%s@%s/%s", name, intranet, extranet);
        return new Agent(id, name, version, intranet, extranet, ctx);
    }

    private String stringify(final SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getHostString();
        }
        return address.toString();
    }

    private String stringify(final Agent agent) {
        if (null != agent) {
            return agent.id + ' ' + agent.name + ' ' + agent.version + " " + agent.extranet + '/' + agent.intranet;
        }
        return null;
    }

    @AllArgsConstructor
    private class Agent {
        private final String id;
        private final String name;
        private final String version;
        private final String intranet;
        private final String extranet;
        private final ChannelHandlerContext bus;
    }

    @AllArgsConstructor
    private class Tunnel {
        private final String id;
        private final Agent agent;
        private final URI target;
        private final ChannelHandlerContext accessCtx;
        private final Promise<ChannelHandlerContext> backhaulCtxPromise;
    }
}
