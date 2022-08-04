package com.github.pangolin.server;

import com.github.pangolin.server.shell.ConsoleLineReader;
import com.github.pangolin.server.shell.GenericLineReader;
import com.github.pangolin.server.shell.LineReader;
import com.github.pangolin.server.shell.WebSocketTunnelShell;
import io.netty.channel.Channel;
import jline.Terminal;
import jline.TerminalFactory;
import jline.console.ConsoleReader;
import org.fusesource.jansi.AnsiConsole;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

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

        final Properties props = new Properties();
        final String forwardHostnameAliasConfig = System.getProperty("forward.hostname.alias.config");
        if (StringUtils.hasText(forwardHostnameAliasConfig)) {
            final File forwardHostnameAliasFile = new File(forwardHostnameAliasConfig);
            if (forwardHostnameAliasFile.exists() && forwardHostnameAliasFile.isFile()) {
                final InputStream in = new FileInputStream(forwardHostnameAliasFile);
                try {
                    props.load(in);
                } finally {
                    in.close();
                }
            }
        }

        /*
        WebSocketTunnelServer webSocketTunnelServer = new WebSocketTunnelServer(2345, "/tunnel", false);
        final Channel channel = webSocketTunnelServer.start();
        channel.closeFuture().await();
        */
        final WebSocketTunnelServer server = new WebSocketTunnelServer(2345, "/tunnel", false);
        final Channel channel = server.start();
        channel.eventLoop().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                server.expiredCheck();
            }
        }, 60, 60, TimeUnit.SECONDS);

        final Terminal terminal = TerminalFactory.create();
        final LineReader lineReader = !terminal.isEchoEnabled() && !terminal.isAnsiSupported() ? new GenericLineReader(System.in, System.out) : new ConsoleLineReader(server, System.in, System.out, terminal);
        new WebSocketTunnelShell(server, lineReader, System.out, (Map) props).run();
        // new WebSocketTunnelShell(server, new GenericLineReader(System.in, System.out), System.out).run();
    }

}
