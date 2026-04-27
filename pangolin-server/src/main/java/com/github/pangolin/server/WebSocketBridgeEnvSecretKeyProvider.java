package com.github.pangolin.server;

import io.netty.util.CharsetUtil;

public class WebSocketBridgeEnvSecretKeyProvider implements WebSocketBridgeSecretKeyProvider {
    public static final WebSocketBridgeEnvSecretKeyProvider INSTANCE = new WebSocketBridgeEnvSecretKeyProvider();

    private WebSocketBridgeEnvSecretKeyProvider() {
    }

    @Override
    public byte[] getSecretKey(String tunnelKey) {
        // XXX: ...
        String secretKey = System.getProperty("websocket.bridge." + tunnelKey + ".secretKey");
        if (null == secretKey || secretKey.isEmpty()) {
            secretKey = new StringBuilder(tunnelKey).reverse().append("^_^").append(tunnelKey).toString();
        }
        return secretKey.getBytes(CharsetUtil.UTF_8);
    }

}
