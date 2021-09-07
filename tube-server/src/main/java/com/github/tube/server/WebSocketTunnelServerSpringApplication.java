package com.github.tube.server;

import com.github.tube.server.shell.ConsoleLineReader;
import com.github.tube.server.shell.GenericLineReader;
import com.github.tube.server.shell.JLineTest;
import com.github.tube.server.shell.WebSocketTunnelShell;
import jline.Terminal;
import jline.TerminalFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WebSocketTunnelServerSpringApplication {

    /**
     * Run spring application.
     *
     * @param args command line args
     */
    public static void main(String[] args) throws Exception {
        final SpringApplication application = new SpringApplication(WebSocketTunnelServerSpringApplication.class);
//        application.addListeners(new ApplicationPidFileWriter());
        application.run(args);

        /*
        WebSocketTunnelServer webSocketTunnelServer = new WebSocketTunnelServer(2345, "/tunnel", false);
        final Channel channel = webSocketTunnelServer.start();
        channel.closeFuture().await();
        */
        final WebSocketTunnelServer server = new WebSocketTunnelServer(2345, "/tunnel", false);
        server.start();
        final Terminal terminal = TerminalFactory.create();
        new WebSocketTunnelShell(server, new ConsoleLineReader(server, System.in, System.out, terminal), System.out).run();
    }

}
