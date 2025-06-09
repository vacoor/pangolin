package com.github.pangolin.agent;

import com.github.pangolin.agent.servlet.WebSocketBridgeEndpoint;
import org.glassfish.tyrus.server.Server;

import javax.websocket.DeploymentException;
import java.util.Collections;
import java.util.concurrent.locks.LockSupport;

public class WebSocketServer {
    public static void main(String[] args) throws DeploymentException {
        final Server server = new Server(
                null, 7777,
                "/",
                Collections.emptyMap(),
                WebSocketBridgeEndpoint.class
        );
        server.start();
        LockSupport.park();
    }
}