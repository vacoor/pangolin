package com.github.pangolin.proxy.codec;

import lombok.Getter;

@Getter
public class WebSocketTunnelRequest {
    private final byte version;
    private final byte command;
    private final byte addressType;
    private final String address;
    private final int port;

    public WebSocketTunnelRequest(final byte version, final byte command, final byte addressType, final String address, final int port) {
        this.version = version;
        this.command = command;
        this.addressType = addressType;
        this.address = address;
        this.port = port;
    }
}