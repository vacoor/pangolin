package com.github.pangolin.routing;

import com.sun.corba.se.impl.activation.ServerMain;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 */
@SpringBootApplication
public class RoutingServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoutingServerApplication.class, args);
        ServerMain.main(args);
    }
}
