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
        } else if ("tunnel".equals(args[0])) {
            if (args.length < 2 || ("remove".equals(args[1]) && args.length < 3)) {
                out.println("Usage: tunnel [ACTION] [TUNNEL]");
                out.println();
                out.println("  list    List information about registered tunnels");
                out.println("  remove  Remove the registered tunnel");
                return;
            }
            final String action = args[1];
            if ("list".equals(action)) {
                final String prefix = args.length > 2 ? args[2] : "";
                final Collection<WebSocketTunnelServer.Broker> nodes = server.getBrokers();
                for (WebSocketTunnelServer.Broker node : nodes) {
                    if (node.name().startsWith(prefix)) {
                        out.println(node);
                    }
                }
            } else if ("remove".equals(action)) {
                final String tunnel = args[2];
                final WebSocketTunnelServer.Broker broker = server.lookupBroker(tunnel);
                if (null == broker) {
                    out.println(String.format("Tunnel '%s' not exists", tunnel));
                } else {
                    broker.close();
                    out.println(String.format("Tunnel '%s' removed", tunnel));
                }
            }
        } else if ("forward".equals(args[0])) {
            if (args.length < 2) {
                out.println("Usage: forward [ACTION] [OPTION]");
                out.println();
                out.println("  list [-l]                             List information about forward rule");
                out.println("  add [L_PORT] [TUNNEL] [R_HOST:R_PORT] Add the forward rule, mapping local L_PORT to remote host R_HOST and port R_PORT by TUNNEL");
                out.println("  remove [L_PORT]                       Remove the forward rule");
                out.println("  kill [LINK_ID]                        Kill the forward link");
                return;
            }
            final String action = args[1];
            if ("list".equals(action)) {
                final String option = args.length > 2 ? args[2] : "";
                final boolean isL = "-l".equals(option);
                Collection<WebSocketTunnelServer.PortForwarding2> forwards = server.getAccessRules();
                for (WebSocketTunnelServer.PortForwarding2 forward : forwards) {
                    out.println(forward);
                    if (isL) {
                        for (WebSocketTunnelServer.Connection link : server.getConnections(forward)) {
                            out.println("  |- " + link);
                        }
                    }
                }
            } else if ("add".equals(action)) {
                final int port = Integer.parseInt(args[2]);
                final String tunnel = args[3];
                final String target = args[4];
                final String[] segments = target.split(":", 2);
                final String hostname = segments[0];
                final int targetPort = Integer.parseInt(segments[1]);
                server.forward(port, tunnel, hostname, targetPort);
                out.println("OK");
            } else if ("remove".equals(action)) {
                final int port = Integer.parseInt(args[2]);
                server.unforward(port);
                out.println("OK");
            } else if ("kill".equals(action)) {
                final String id = args[2];
                boolean kill = server.kill(id);
                if (kill) {
                    out.println("Killed");
                } else {
                    out.println(String.format("'%s' not found", id));
                }
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