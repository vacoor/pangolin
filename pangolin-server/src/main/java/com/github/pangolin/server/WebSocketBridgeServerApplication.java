package com.github.pangolin.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 */
@SpringBootApplication
public class WebSocketBridgeServerApplication {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(WebSocketBridgeServerApplication.class, args);
        WebSocketBridgeServer.main(args);
    }

}
