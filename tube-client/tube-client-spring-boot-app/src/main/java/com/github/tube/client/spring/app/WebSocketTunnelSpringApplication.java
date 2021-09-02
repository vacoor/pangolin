package com.github.tube.client.spring.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WebSocketTunnelSpringApplication {

    /**
     * Run spring application.
     *
     * @param args command line args
     */
    public static void main(String[] args) throws Exception {
        final SpringApplication application = new SpringApplication(WebSocketTunnelSpringApplication.class);
        application.run(args);
    }

}
