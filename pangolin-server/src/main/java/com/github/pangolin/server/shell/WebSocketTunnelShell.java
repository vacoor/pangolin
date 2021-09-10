package com.github.pangolin.server.shell;

import com.github.pangolin.server.WebSocketTunnelServer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSocketTunnelShell {
    private boolean breakOnNull;
    private WebSocketTunnelServer server;

    protected final LineReader reader;
    protected final PrintStream output;
    protected volatile boolean running = false;

    public WebSocketTunnelShell(final WebSocketTunnelServer server,
                                final LineReader reader, final PrintStream output) {
        this.server = server;
        this.reader = reader;
        this.output = output;
    }

    public void run() throws IOException {
        running = true;
        output.println();
        output.println("Welcome to WebSocket Tunnel!");
        output.println();
        output.flush();
        while (running && next()) {

        }
    }

    public void start() {
        new Thread() {
            @Override
            public void run() {
                try {
                    WebSocketTunnelShell.this.run();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    public boolean next() throws IOException {
        final String line = reader.readLine();
        if (null == line && breakOnNull) {
            return false;
        }

        final String lineToUse = null != line ? line.trim() : "";
        if (lineToUse.length() > 0) {
            this.execute(lineToUse);
        }
        return true;
    }

    public static final Pattern ARGS_PATTERN = Pattern.compile("\\s*([^\"\']\\S*|\"[^\"]*\"|'[^']*')\\s*");
    public static final Pattern QUOTED_PATTERN = Pattern.compile("^([\'\"])(.*)(\\1)$");

    protected void execute(final String line) {
        try {
            doExecute(line, output);
        } catch (final Throwable ex) {
            output.println(ex.getMessage());
        }
    }

    protected void doExecute(final String line, final PrintStream out) throws Exception {
        // find command and execute
        final String[] args = tokenize(line);
        if (0 == args.length) {
            return;
        }

        if ("exit".equals(args[0]) || "quit".equals(args[0])) {
            running = false;
            out.println("Exit");
            reader.close();
            server.shutdownGracefully();
        } else if ("stat".equals(args[0])) {
            Collection<WebSocketTunnelServer.Broker> nodes = server.getNodes();
            for (WebSocketTunnelServer.Broker node : nodes) {
                out.println(node);
            }
        } else if ("ls".equals(args[0])) {
            final boolean isL = args.length > 1  && "-l".equals(args[1]);
            Collection<WebSocketTunnelServer.BrokerForwarding> forwards = server.getAccessRules();
            for (WebSocketTunnelServer.BrokerForwarding forward : forwards) {
                out.println(forward);
                if (isL) {
                    for (WebSocketTunnelServer.TunnelLink link : server.getTunnelLink(forward)) {
                        out.println("  |- " + link);
                    }
                }
            }
        } else if ("forward".equals(args[0])) {
            final int port = Integer.parseInt(args[1]);
            final String tunnel = args[2];
            final String target = args[3];
            final String[] segments = target.split(":", 2);
            final String hostname = segments[0];
            final int targetPort = Integer.parseInt(segments[1]);
            server.forward(port, tunnel, hostname, targetPort);
            out.println("OK");
        } else if ("unforward".equals(args[0])) {
            final int port = Integer.parseInt(args[1]);
            server.unforward(port);
            out.println("OK");
        } else if ("kill".equals(args[0])) {
            final String id = args[1];
            boolean kill = server.kill(id);
            if (kill) {
                out.println("Killed");
            } else {
                out.println(String.format("'%s' not found", id));
            }
        } else {
            out.println(String.format("%s: command not found", args[0]));
        }
    }
    /*-
        tunnels     List tunnels
        ps          List tunnel streams
        kill        Kill one or more running tunnel streams

        listen      Listen host port and forward to target by tunnel
        rm          Remove one or more port mappings
     */

    private String[] tokenize(final String line) {
        final Matcher matcher = ARGS_PATTERN.matcher(line);
        List<String> args = new LinkedList<String>();
        while (matcher.find()) {
            String value = matcher.group(1);
            if (QUOTED_PATTERN.matcher(value).matches()) {
                // Strip off the surrounding quotes
                value = value.substring(1, value.length() - 1);
            }
            args.add(value);
        }
        return args.toArray(new String[args.size()]);
    }

    public static void main(String[] args) throws Exception {
        final WebSocketTunnelServer server = new WebSocketTunnelServer("0.0.0.0", 2345, "/tunnel", false);
        server.start();
        new WebSocketTunnelShell(server, new GenericLineReader(System.in, System.out), System.out).run();
    }
}