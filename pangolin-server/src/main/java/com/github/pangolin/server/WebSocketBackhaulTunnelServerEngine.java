package com.github.pangolin.server;

import static io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 *
 */
@Slf4j
public class WebSocketBackhaulTunnelServerEngine {
    private static final String AGENT_NAME = "X-Node-Name";
    private static final String AGENT_VERSION = "X-Node-Version";
    private static final String AGENT_INTRANET = "X-Node-Intranet";

    private static final byte IPv4_ADDR_SIZE = 4;
    private static final byte IPv6_ADDR_SIZE = 16;

    private static final byte VER_1 = 0x01;

    private static final byte CMD_CONNECT = 0x01;

    private static final byte ATYPE_IPv4 = 0x01;
    private static final byte ATYPE_DOMAIN = 0x03;
    private static final byte ATYPE_IPv6 = 0x04;

    private static final byte REPLY_SUCCESS = 0x00;
    private static final byte REPLY_FAILURE = 0x01;
    private static final byte REPLY_FORBIDDEN = 0x02;
    private static final byte REPLY_NETWORK_UNREACHABLE = 0x03;
    private static final byte REPLY_HOST_UNREACHABLE = 0x04;
    private static final byte REPLY_CONNECTION_REFUSED = 0x05;
    private static final byte REPLY_TTL_EXPIRED = 0x06;
    private static final byte REPLY_COMMAND_UNSUPPORTED = 0x07;
    private static final byte REPLY_ADDRESS_UNSUPPORTED = 0x08;

    /**
     * Registered agents.
     */
    private final ConcurrentMap<String, Agent> registeredAgents = new ConcurrentHashMap<>();

    /**
     * Requested connections.
     */
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * Agent registered.
     *
     * @param handshake the handshake information
     * @param agentCtx  the agent channel handler context
     * @return true if registered, otherwise false
     */
    boolean agentRegistered(final HandshakeComplete handshake, final ChannelHandlerContext agentCtx) {
        final Agent agent = this.createAgent(handshake, agentCtx);
        if (null == registeredAgents.putIfAbsent(agent.id, agent)) {
            log.info("[Agent] Agent registered: {}", stringify(agent));

            agentCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    log.info("[Agent] Agent connection closed: {}", stringify(agent));

                    agentUnregistered(agent);
                }
            });
            return true;
        }
        return false;
    }

    private Agent createAgent(final HandshakeComplete handshake, final ChannelHandlerContext agentCtx) {
        final HttpHeaders headers = handshake.requestHeaders();
        final String name = headers.getAsString(AGENT_NAME);
        final String version = headers.getAsString(AGENT_VERSION);
        final String intranet = headers.getAsString(AGENT_INTRANET);
        final String extranet = stringify(agentCtx.channel().remoteAddress());

        final String id = agentCtx.channel().id().toString();
        return new Agent(id, name, version, intranet, extranet, agentCtx);
    }

    /**
     * Agent unregistered.
     *
     * @param agent the agent to unregistered
     */
    private void agentUnregistered(final Agent agent) {
        if (registeredAgents.remove(agent.id, agent)) {
            log.info("[Agent] Agent unregistered: {}", stringify(agent));
            if (agent.bus.channel().isActive()) {
                agent.bus.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    /**
     * Initiating a handshake with the agent for the given target address.
     *
     * @param accessCtx       the access channel context
     * @param agentKey        the connection agent key
     * @param target          the connection target address
     * @param backhaulPromise the handshake promise
     * @return the handshake promise
     */
    public Promise<ChannelHandlerContext> handshake(final ChannelHandlerContext accessCtx,
                                                    final String agentKey, final InetSocketAddress target,
                                                    final Promise<ChannelHandlerContext> backhaulPromise) {
        return handshake(accessCtx, agentKey, target, 10, TimeUnit.SECONDS, backhaulPromise);
    }

    /**
     * Initiating a handshake with the agent for the given target address.
     *
     * @param accessCtx        the access channel context
     * @param agentKey         the connection agent key
     * @param target           the connection target address
     * @param timeout          the maximum time to wait
     * @param unit             the time unit of the {@code timeout} argument
     * @param handshakePromise the handshake promise
     * @return the handshake promise
     */
    public Promise<ChannelHandlerContext> handshake(final ChannelHandlerContext accessCtx,
                                                    final String agentKey, final InetSocketAddress target,
                                                    final long timeout, final TimeUnit unit,
                                                    final Promise<ChannelHandlerContext> handshakePromise) {
        final Agent agent = this.choose(agentKey);
        if (null == agent) {
            handshakePromise.tryFailure(new ConnectException(String.format("The agent not found: '%s'", agentKey)));
            return handshakePromise;
        }

        final String id = accessCtx.channel().id().toString();
        final Connection connection = new Connection(id, agent, target, accessCtx, handshakePromise);
        if (null != connections.putIfAbsent(id, connection)) {
            handshakePromise.tryFailure(new ConnectException(String.format("The access context with '%s' is already in use", id)));
            return handshakePromise;
        }

        log.info("[{}] Initiating handshake with {} for {}", id, simplify(agent), target);

        accessCtx.channel().config().setAutoRead(false);
        accessCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try {
                    log.info("[{}] Connection closed: {} -> {}", id, stringify(accessCtx.channel().remoteAddress()), simplify(agent));

                    if (!handshakePromise.isDone()) {
                        handshakePromise.tryFailure(new IOException("Connection closed"));
                    } else if (handshakePromise.isSuccess()) {
                        /*-
                         * Close the backhaul connection upon access connection closed.
                         *
                         * @see #finishHandshake
                         */
                        final Channel backhaul = handshakePromise.getNow().channel();
                        if (backhaul.isActive()) {
                            backhaul.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                } finally {
                    connections.remove(id, connection);
                }
            }
        });

        /*-
         * Handshake timeout.
         */
        final ScheduledFuture<?> handshakeTimeoutFuture = accessCtx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                if (!handshakePromise.isDone()) {
                    final long timeoutMs = unit.toMillis(timeout);
                    log.warn(
                            "[{}] Handshake timeout ({}ms) with {} for {}",
                            id, timeoutMs, simplify(agent), target
                    );
                    handshakePromise.tryFailure(new ConnectTimeoutException("Handshake timeout"));
                }
            }
        }, timeout, unit);

        handshakePromise.addListener(new GenericFutureListener<Future<ChannelHandlerContext>>() {
            @Override
            public void operationComplete(Future<ChannelHandlerContext> future) throws Exception {
                // clear handshake timeout
                if (!handshakeTimeoutFuture.isDone()) {
                    handshakeTimeoutFuture.cancel(false);
                }
            }
        });

        handshake0(id, accessCtx, agent, target);

        return handshakePromise;
    }

    /**
     * Choose a agent for agent key.
     *
     * @param agentKey the agent key
     * @return the agent instance or null
     */
    private Agent choose(final String agentKey) {
        Agent agent = registeredAgents.get(agentKey);
        if (null != agent) {
            return agent;
        }

        final List<Agent> candidates = new ArrayList<>();
        for (final Agent candidate : registeredAgents.values()) {
            if (candidate.getName().equals(agentKey)) {
                candidates.add(candidate);
            }
        }
        return !candidates.isEmpty() ? candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())) : null;
    }

    /**
     * @param id        the connection id
     * @param accessCtx the access channel context
     * @param agent     the connection agent
     * @param target    the connection target address
     */
    private void handshake0(final String id,
                            final ChannelHandlerContext accessCtx,
                            final Agent agent, final InetSocketAddress target) {
        if ("1.0".equals(agent.version)) {
            final String command = id + "->" + "tcp://" + target.getHostString() + ":" + target.getPort();

            log.info("[{}] Sending {} handshake to {} for {}", id, command, simplify(agent), target);

            agent.bus.writeAndFlush(new TextWebSocketFrame(command));
            return;
        }

        /*-
         * TCP connect request is a SOCKS5-like request:
         *
         * +-----+----------+-----+-------+------+----------+----------+
         * | VER | ID       | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
         * +-----+----------+-----+-------+------+----------+----------+
         * |  1  | Variable |  1  | X'00' |  1   | Variable |    2     |
         * +-----+----------+-----+-------+------+----------+----------+
         *
         * Where:
         * o  VER protocol version: X'01'
         * o  ID request id
         * o  CMD
         *    o  CONNECT X'01'
         *    o  UDP ASSOCIATE X'03'
         * o  RSV X'00'
         * o  ATYP address type of following address
         *    o  IP V4 address: X'01'
         *    o  DOMAINNAME: X'03'
         *    o  IP V6 address: X'04'
         * o  DST.ADDR desired destination address
         * o  DST.PORT desired destination port in network octet order
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
        buffer.writeByte(idBytes.remaining());
        buffer.writeBytes(idBytes);
        buffer.writeByte(CMD_CONNECT);
        buffer.writeByte(0);

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

        if (log.isInfoEnabled()) {
            final String hex = ByteBufUtil.hexDump(buffer);
            log.info("[{}] Sending {} handshake to {} for {}", id, hex, simplify(agent), target);
        }

        agent.bus.writeAndFlush(new BinaryWebSocketFrame(buffer));
    }

    void agentResponded(final BinaryWebSocketFrame message, final ChannelHandlerContext agentCtx) {
        /*-
         * The reply is a SOCKS5-like reply:
         *
         * +----+----------+-----+-------+------+----------+----------+
         * |VER | ID       | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
         * +----+----------+-----+-------+------+----------+----------+
         * | 1  | Variable |  1  | X'00' |  1   | Variable |    2     |
         * +----+----------+-----+-------+------+----------+----------+
         */
        final ByteBuf in = message.content();
        final byte version = in.readByte();
        Preconditions.checkState(VER_1 == version, "unsupported version: %s, (expected: %s)", version, VER_1);

        final String id = in.readCharSequence(in.readByte(), CharsetUtil.UTF_8).toString();
        final byte status = in.readByte();
        in.skipBytes(1);

        if (REPLY_SUCCESS != status) {
            final Connection connection = connections.get(id);
            if (null != connection) {
                connection.backhaulPromise.tryFailure(new ConnectException("host unreachable: " + status));
            }
        }
    }

    /**
     * Validates and finishes the opening handshake initiated by {@link #handshake}.
     *
     * @param id          the id of handshake request
     * @param backhaulCtx the backhaul channel
     * @return true if handshake completed otherwise false
     */
    public boolean finishHandshake(final String id, final ChannelHandlerContext backhaulCtx) {
        backhaulCtx.channel().config().setAutoRead(false);

        final Connection connection = null != id ? connections.get(id) : null;
        if (null == connection) {
            log.info("[{}] Pending handshake context not found", id);
            // backhaulCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            return false;
        }

        final Agent agent = connection.agent;
        final ChannelHandlerContext accessCtx = connection.accessCtx;
        if (!connection.backhaulPromise.isDone() && connection.backhaulPromise.trySuccess(backhaulCtx)) {
            log.info("[{}] Handshake completed: {} -{}-> {}", id, stringify(accessCtx.channel().remoteAddress()), simplify(agent), connection.target);

            backhaulCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    /*-
                     * Close the access connection upon backhaul connection closed.
                     */
                    log.info("[{}] Connection closed: {} -> {}", id, simplify(agent), connection.target);
                    if (accessCtx.channel().isActive()) {
                        accessCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                }
            });
            return true;
        } else {
            log.warn("[{}] Connection already established: {} -{}-> {}", id, stringify(accessCtx.channel().remoteAddress()), simplify(agent), connection.target);
            // backhaulCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            return false;
        }
    }

    public Collection<Agent> getAgents() {
        return registeredAgents.values();
    }

    public Collection<Connection> getConnections() {
        return connections.values();
    }

    public boolean kill(final String connectionId) throws InterruptedException {
        final Connection connection = connections.get(connectionId);
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
        private final String version;
        private final String intranet;
        private final String extranet;
        private final ChannelHandlerContext bus;
    }

    @AllArgsConstructor
    public class Connection {
        private final String id;
        private final Agent agent;
        private final InetSocketAddress target;
        private final ChannelHandlerContext accessCtx;
        private final Promise<ChannelHandlerContext> backhaulPromise;
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
            return agent.id + ' ' + agent.name + ' ' + agent.version + " " + agent.extranet + '/' + agent.intranet;
        }
        return null;
    }

}
