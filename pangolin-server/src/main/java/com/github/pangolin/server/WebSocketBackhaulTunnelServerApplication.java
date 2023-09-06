package com.github.pangolin.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 */
@SpringBootApplication
public class WebSocketBackhaulTunnelServerApplication {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(WebSocketBackhaulTunnelServerApplication.class, args);
        WebSocketBackhaulTunnelServer.main(args);
    }

}
