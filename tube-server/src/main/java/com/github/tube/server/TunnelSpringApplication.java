package com.github.tube.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TunnelSpringApplication {

    /**
     * Run spring application.
     *
     * @param args command line args
     */
    public static void main(String[] args) throws Exception {
        final SpringApplication application = new SpringApplication(TunnelSpringApplication.class);
//        application.addListeners(new ApplicationPidFileWriter());
        application.run(args);
        WebSocketTunnelServer.main(args);
    }

}
