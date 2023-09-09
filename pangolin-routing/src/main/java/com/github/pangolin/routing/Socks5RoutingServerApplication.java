package com.github.pangolin.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 */
@SpringBootApplication
public class Socks5RoutingServerApplication {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(Socks5RoutingServerApplication.class, args);
        Socks5RoutingServer.main(args);
    }

}
