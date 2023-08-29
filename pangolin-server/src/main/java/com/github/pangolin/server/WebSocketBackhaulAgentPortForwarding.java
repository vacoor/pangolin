package com.github.pangolin.server;

import java.net.SocketAddress;

public class WebSocketBackhaulAgentPortForwarding {
    private final WebSocketBackhaulProxyServer server;
    private final String agentKey;
    private final SocketAddress target;

    public WebSocketBackhaulAgentPortForwarding(final WebSocketBackhaulProxyServer server, final String agentKey, final SocketAddress target) {
        this.server = server;
        this.agentKey = agentKey;
        this.target = target;
    }


}