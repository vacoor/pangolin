package com.github.pangolin.server;

import com.github.pangolin.util.Util;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.util.Constants.*;

/**
 *
 */
@Slf4j
public class WebSocketBridgeServerEngine {

    /**
     * The default handshake timeout in milliseconds.
     */
    private static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10 * 1000;

    /**
     * The registered agents.
     */
    private final ConcurrentMap<String, Agent> registeredAgents = new ConcurrentHashMap<>();

    /**
     * The requested connections.
     */
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * The handshake timeout in milliseconds.
     */
    private final long handshakeTimeoutMs;
    private final WebSocketBridgeSecretKeyProvider secretKeyProvider;

    public WebSocketBridgeServerEngine() {
        this(DEFAULT_HANDSHAKE_TIMEOUT_MILLIS, WebSocketBridgeEnvSecretKeyProvider.INSTANCE);
    }

    public WebSocketBridgeServerEngine(final WebSocketBridgeSecretKeyProvider secretKeyProvider) {
        this(DEFAULT_HANDSHAKE_TIMEOUT_MILLIS, secretKeyProvider);
    }

    public WebSocketBridgeServerEngine(final long handshakeTimeoutMs, final WebSocketBridgeSecretKeyProvider secretKeyProvider) {
        this.handshakeTimeoutMs = handshakeTimeoutMs;
        this.secretKeyProvider = secretKeyProvider;
    }

    /**
     * Register an agent.
     *
     * @param tunnelKey the tunnel key
     * @param name      the agent name
     * @param intranet  the agent intranet address
     * @param agentCtx  the agent channel context
     * @return true if registered, otherwise false
     */
    boolean registerAgent(final String tunnelKey, final String name,
                          final String intranet, final ChannelHandlerContext agentCtx) {
        final Channel ch = agentCtx.channel();
        final String extranet = stringify(ch.remoteAddress());
        final Agent agent = new Agent(ch.id().toString(), name, intranet, extranet, tunnelKey, agentCtx);
        return registerAgent(agent, agentCtx);
    }

    /**
     * Register an agent.
     *
     * @param agent    the agent
     * @param agentCtx the agent channel context
     * @return true if registered, otherwise false
     */
    private boolean registerAgent(final Agent agent, final ChannelHandlerContext agentCtx) {
        if (null == registeredAgents.putIfAbsent(agent.id, agent)) {
            log.info("[Agent] Agent registered: {}", stringify(agent));

            agentCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    log.info("[Agent] Agent connection closed: {}", stringify(agent));

                    unregisterAgent(agent);
                }
            });
            return true;
        }
        return false;
    }

    /**
     * Unregister an agent.
     *
     * @param agent the agent
     */
    private void unregisterAgent(final Agent agent) {
        if (registeredAgents.remove(agent.id, agent)) {
            log.info("[Agent] Agent unregistered: {}", stringify(agent));
            if (agent.bus.channel().isActive()) {
                agent.bus.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    /**
     * Initiate a handshake through the tunnel for the given target address.
     *
     * @param accessCtx the access channel context
     * @param tunnelKey the tunnel key
     * @param target    the target address
     * @return the handshake promise
     */
    public Promise<ChannelHandlerContext> handshake(final ChannelHandlerContext accessCtx,
                                                    final String tunnelKey, final InetSocketAddress target) {
        return handshake(accessCtx, tunnelKey, target, handshakeTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Initiate a handshake through the tunnel for the given target address.
     *
     * @param accessCtx        the access channel context
     * @param tunnelKey        the tunnel key
     * @param target           the target address
     * @param handshakeTimeout the maximum time to wait
     * @param unit             the time unit of the {@code handshakeTimeout} argument
     * @return the handshake promise
     */
    private Promise<ChannelHandlerContext> handshake(final ChannelHandlerContext accessCtx,
                                                     final String tunnelKey, final InetSocketAddress target,
                                                     final long handshakeTimeout, final TimeUnit unit) {
        final Promise<ChannelHandlerContext> handshakePromise = accessCtx.executor().newPromise();
        final Agent agent = this.choose(tunnelKey);
        if (null == agent) {
            handshakePromise.tryFailure(new ConnectException(String.format("The agent not found: '%s'", tunnelKey)));
            return handshakePromise;
        }

        final String id = accessCtx.channel().id().toString();
        final SocketAddress source = accessCtx.channel().remoteAddress();
        final Connection connection = new Connection(id, agent, source, target, accessCtx, handshakePromise);
        if (null != connections.putIfAbsent(id, connection)) {
            handshakePromise.tryFailure(new ConnectException(String.format("The access context with '%s' is already in use", id)));
            return handshakePromise;
        }

        log.info("[{}] Initiating handshake with {} for {}", id, simplify(agent), target);

        accessCtx.channel().config().setAutoRead(false);

        /*-
         * Apply handshake timeout.
         */
        ScheduledFuture<?> handshakeTimeoutFuture = null;
        if (handshakeTimeout > 0) {
            handshakeTimeoutFuture = accessCtx.executor().schedule(new Runnable() {
                @Override
                public void run() {
                    if (!handshakePromise.isDone()) {
                        final long timeoutMs = unit.toMillis(handshakeTimeout);
                        log.warn("[{}] Handshake timeout ({}ms) with {} for {}", id, timeoutMs, simplify(agent), target);
                        handshakePromise.tryFailure(new ConnectTimeoutException("Handshake timeout"));
                    }
                }
            }, handshakeTimeout, unit);
        }

        /*-
         * Complete the handshake.
         */
        final ScheduledFuture<?> handshakeTimeoutFutureToUse = handshakeTimeoutFuture;
        handshakePromise.addListener(new GenericFutureListener<Future<ChannelHandlerContext>>() {
            @Override
            public void operationComplete(Future<ChannelHandlerContext> future) throws Exception {
                /*-
                 * Clear the handshake timeout.
                 */
                if (null != handshakeTimeoutFutureToUse && !handshakeTimeoutFutureToUse.isDone()) {
                    handshakeTimeoutFutureToUse.cancel(false);
                }

                if (!future.isSuccess()) {
                    log.warn("[{}] Handshake failed with {} for {}: {}", id, simplify(agent), target, future.cause().getMessage());
                    connections.remove(id, connection);
                    if (accessCtx.channel().isActive()) {
                        accessCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                }

                /*-
                 * After a successful handshake, close access when the backhaul is closed.
                 */
                final ChannelHandlerContext backhaulCtx = future.getNow();
                backhaulCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        /*-
                         * Close the access connection when the backhaul connection is closed.
                         */
                        if (accessCtx.channel().isActive()) {
                            log.info("[{}] Connection closed by agent: {} -> {}", id, simplify(agent), connection.target);

                            accessCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        } else {
                            log.info("[{}] Connection closed: {} -> {}", id, simplify(agent), connection.target);
                        }
                    }
                });
            }
        });

        /*-
         * When access is closed, abort the handshake or close the backhaul connection.
         */
        accessCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try {
                    if (!handshakePromise.isDone()) {
                        log.warn("[{}] Handshake aborted by client: {} -> {}", id, stringify(source), simplify(agent));

                        handshakePromise.tryFailure(new IOException("Connection closed"));
                    } else if (handshakePromise.isSuccess()) {
                        /*-
                         * Close the backhaul connection when the access connection is closed.
                         *
                         * @see #finishHandshake
                         */
                        final Channel backhaul = handshakePromise.getNow().channel();
                        if (backhaul.isActive()) {
                            log.info("[{}] Connection closed by client: {} -> {}", id, stringify(source), simplify(agent));

                            backhaul.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        } else {
                            log.info("[{}] Connection closed: {} -> {}", id, stringify(source), simplify(agent));
                        }
                    }
                } finally {
                    connections.remove(id, connection);
                }
            }
        });

        handshake0(id, accessCtx, agent, target, handshakePromise);

        return handshakePromise;
    }

    /**
     * Choose an agent for the tunnel key.
     *
     * @param tunnelKey the tunnel key
     * @return the agent instance or null
     */
    private Agent choose(final String tunnelKey) {
        final List<Agent> candidates = new ArrayList<>();
        for (final Agent candidate : registeredAgents.values()) {
            if (candidate.getTunnelKey().equals(tunnelKey)) {
                candidates.add(candidate);
            }
        }
        return !candidates.isEmpty() ? candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())) : null;
    }

    /**
     * Send a handshake request to the agent.
     *
     * @param id        the connection id
     * @param accessCtx the access channel context
     * @param agent     the agent
     * @param target    the target address
     */
    private void handshake0(final String id,
                            final ChannelHandlerContext accessCtx,
                            final Agent agent, final InetSocketAddress target,
                            final Promise<?> handshakePromise) {
        /*-
         * TCP connect request is a SOCKS5-like request
         * (sent over already-authenticated SERVICE bus, NO auth tail):
         *
         * +-----+-----+-------+------+----------+----------+----------+
         * | VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT | ID       |
         * +-----+-----+-------+------+----------+----------+----------+
         * |  1  |  1  | X'00' |  1   | Variable |    2     | Variable |
         * +-----+-----+-------+------+----------+----------+----------+
         *
         * Where:
         * o  VER protocol version: X'01'
         * o  CMD
         *    o  CONNECT X'01'
         *    o  UDP ASSOCIATE X'03'
         * o  RSV X'00'
         * o  ATYP address type of following address
         *    o  IP V4 address: X'01'
         *    o  DOMAIN NAME: X'03'
         *    o  IP V6 address: X'04'
         * o  DST.ADDR desired destination address
         * o  DST.PORT desired destination port in network octet order
         * o  ID request id, 1B length prefix + UTF-8 bytes
         *
         * In an address field (DST.ADDR, BND.ADDR), the ATYP field specifies
         * the type of address contained within the field:
         *
         *        o  X'01'
         *
         * the address is a version-4 IP address, with a length of 4 octets
         *
         *        o  X'03'
         *
         * the address field contains a fully-qualified domain name.  The first
         * octet of the address field contains the number of octets of name that
         * follow, there is no terminating NUL octet.
         *
         *        o  X'04'
         *
         * the address is a version-6 IP address, with a length of 16 octets.
         */
        final ByteBuffer idBytes = CharsetUtil.UTF_8.encode(id);
        final ByteBuf buffer = accessCtx.alloc().buffer();
        buffer.writeByte(VER_1);
        buffer.writeByte(CMD_CONNECT);
        buffer.writeByte(RSV);

        if (target.isUnresolved()) {
            final String hostname = target.getHostString();
            final byte[] bytes = hostname.getBytes(CharsetUtil.UTF_8);
            buffer.writeByte(ATYPE_DOMAIN);
            buffer.writeByte(bytes.length);
            buffer.writeBytes(bytes);
        } else {
            final InetAddress address = target.getAddress();
            if (address instanceof Inet4Address) {
                buffer.writeByte(ATYPE_IPv4);
            } else if (address instanceof Inet6Address) {
                buffer.writeByte(ATYPE_IPv6);
            } else {
                throw new UnsupportedOperationException(address.toString());
            }
            buffer.writeBytes(address.getAddress());
        }

        buffer.writeShort(target.getPort());
        buffer.writeByte(idBytes.remaining());
        buffer.writeBytes(idBytes);

        if (log.isInfoEnabled()) {
            final String hex = ByteBufUtil.hexDump(buffer);
            log.info("[{}] Sending {} handshake to {}", id, hex, simplify(agent));
        }

        /*-
         * Send the request to the agent.
         */
        agent.bus.writeAndFlush(new BinaryWebSocketFrame(buffer)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    handshakePromise.tryFailure(future.cause());
                }
            }
        });
    }

    /**
     * Handle a response from the agent.
     *
     * @param payload  the response payload
     * @param agentCtx the agent channel context
     */
    void agentResponded(final String tunnelKey, final ByteBuf payload, final ChannelHandlerContext agentCtx) throws UnknownHostException {
        /*-
         * The reply is a SOCKS5-like reply (sent over already-authenticated SERVICE bus,
         * NO auth tail; only used for failure replies — successful replies travel as the
         * BACKHAUL handshake token where auth tail IS required):
         *
         * +-----+-----+-------+------+----------+----------+----------+
         * | VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT | ID       |
         * +-----+-----+-------+------+----------+----------+----------+
         * |  1  |  1  | X'00' |  1   | Variable |    2     | Variable |
         * +-----+-----+-------+------+----------+----------+----------+
         */
        final byte version = payload.readByte();
        if (VER_1 != version) {
            log.warn("[AGENT] Unsupported version: {}, (expected: {})", version, VER_1);
            return;
        }

        final byte status = payload.readByte();
        payload.skipBytes(1);
        Util.skipSocketAddress(payload);

        final String id = payload.readString(payload.readUnsignedByte(), CharsetUtil.UTF_8);
        if (REPLY_SUCCESS != status) {
            final Connection connection = connections.get(id);
            if (null != connection) {
                connection.handshakePromise.tryFailure(new ConnectException("Host unreachable: " + status));
            }
        }
    }

    /**
     * Validate and finish the opening handshake initiated by {@link #handshake}.
     *
     * @param tunnelKey   the tunnel key
     * @param id          the connection id
     * @param backhaulCtx the backhaul channel context
     * @return true if the handshake completes successfully; otherwise false
     */
    public boolean finishHandshake(final String tunnelKey,
                                   final String id, final ChannelHandlerContext backhaulCtx) {
        backhaulCtx.channel().config().setAutoRead(false);

        final Connection connection = null != id ? connections.get(id) : null;
        if (null == connection) {
            log.info("[{}] Pending handshake not found for id {}", id, id);
            return false;
        }

        final Agent agent = connection.agent;
        if (!agent.tunnelKey.equals(tunnelKey)) {
            log.warn("[{}] Mismatched tunnel key: {}, expected: {}", id, tunnelKey, agent.tunnelKey);
            return false;
        }

        final ChannelHandlerContext accessCtx = connection.accessCtx;
        final SocketAddress accessAddr = accessCtx.channel().remoteAddress();
        if (!connection.handshakePromise.isDone() && connection.handshakePromise.trySuccess(backhaulCtx)) {
            log.info("[{}] Handshake completed: {} -{}-> {}", id, stringify(accessAddr), simplify(agent), connection.target);
            return true;
        } else {
            log.warn("[{}] Connection already established: {} -{}-> {}", id, stringify(accessAddr), simplify(agent), connection.target);
            return false;
        }
    }

    byte[] hmac(final String tunnelKey, final ByteBuf payload, final int offset, final int len) {
        final byte[] secretKey = secretKeyProvider.getSecretKey(tunnelKey);
        return null != secretKey ? Util.hmacSha256(payload, offset, len, secretKey) : null;
    }

    /* ****************** */

    public Collection<Agent> getAgents() {
        return registeredAgents.values();
    }

    public Collection<Connection> getConnections() {
        return connections.values();
    }

    public boolean kill(final String id) throws InterruptedException {
        final Connection connection = connections.get(id);
        if (null != connection) {
            connection.accessCtx.close().sync();
        }
        return null != connection;
    }

    @Getter
    @AllArgsConstructor
    public static class Agent {
        private final String id;
        private final String name;
        private final String intranet;
        private final String extranet;

        private final String tunnelKey;
        private final ChannelHandlerContext bus;
    }

    @Getter
    @AllArgsConstructor
    public class Connection {
        private final String id;
        private final Agent agent;
        private final SocketAddress source;
        private final SocketAddress target;
        private final ChannelHandlerContext accessCtx;
        private final Promise<ChannelHandlerContext> handshakePromise;
    }

    private String stringify(final SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getHostString();
        }
        return null != address ? address.toString() : null;
    }

    private String simplify(final Agent agent) {
        return agent.name + '(' + agent.extranet + '/' + agent.intranet + ')';
    }

    private String stringify(final Agent agent) {
        if (null != agent) {
            return agent.tunnelKey + ' ' + agent.id + ' ' + agent.name + ' ' + agent.extranet + '/' + agent.intranet;
        }
        return null;
    }

}
