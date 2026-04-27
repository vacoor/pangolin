package com.github.pangolin.server;

public interface WebSocketBridgeSecretKeyProvider {

    byte[] getSecretKey(final String tunnelKey);

}
