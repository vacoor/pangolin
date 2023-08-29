package com.github.pangolin.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230828
 */
public class WebSocketBackhaulAgentExchanger {
    private static final String AGENT_NAME = "X-Node-Name";
    private static final String AGENT_VERSION = "X-Node-Version";
    private static final String AGENT_INTRANET = "X-Node-Intranet";

    /**
     * 已注册的broker节点(id:agent).
     */
    private final ConcurrentMap<String, Agent> registeredAgents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Tunnel> tunnelMap = new ConcurrentHashMap<>();

    void agentRegistered(final ChannelHandlerContext ctx, final HandshakeComplete handshake) {
        final HttpHeaders headers = handshake.requestHeaders();
        final String name = headers.getAsString(AGENT_NAME);
        final String version = headers.getAsString(AGENT_VERSION);
        final String intranet = headers.getAsString(AGENT_INTRANET);
        final String extranet = stringify(ctx.channel().remoteAddress());
        final String id = String.format("%s@%s/%s", name, intranet, extranet);
    }

    private String stringify(final SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getHostString();
        }
        return address.toString();
    }

    private class Agent {

    }

    private class Tunnel {

    }
}
