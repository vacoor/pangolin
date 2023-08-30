package com.github.pangolin.server.shell;

import com.github.pangolin.server.Discover;
import com.github.pangolin.server.WebSocketBackhaulProxyServer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Shell {
    private boolean breakOnNull;
    private WebSocketBackhaulProxyServer server;

    protected final LineReader reader;
    protected final PrintStream output;
    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected Discover discover;

    private Map<String, String> forwardHostnameAliasMap;

    public Shell(final WebSocketBackhaulProxyServer server, final LineReader reader, final PrintStream output,
                 final Map<String, String> forwardHostnameAliasMap) {
        this.server = server;
        this.reader = reader;
        this.output = output;
        this.forwardHostnameAliasMap = null != forwardHostnameAliasMap ? forwardHostnameAliasMap : new HashMap<String, String>();
    }

    public void run() throws IOException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        output.println();
        output.println("Welcome to WebSocket Tunnel!");
        output.println();
        output.flush();
        while (started.get() && next()) {

        }
    }

    public void start() {
        new Thread() {
            @Override
            public void run() {
                try {
                    Shell.this.run();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    private boolean next() throws IOException {
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
            started.set(false);
            out.println("Exit");
            reader.close();
            server.shutdownGracefully();
        } else if ("agent".equals(args[0])) {
            if (args.length < 2 || ("remove".equals(args[1]) && args.length < 3)) {
                out.println("Usage: agent [ACTION] [TUNNEL]");
                out.println();
                out.println("  list    List information about registered agents");
                out.println("  remove  Remove the registered agent");
                return;
            }
            final String action = args[1];
            if ("list".equals(action)) {
                final String prefix = args.length > 2 ? args[2] : "";
                final Collection<WebSocketBackhaulProxyServer.Agent> nodes = null;//server.getAgents();
                for (WebSocketBackhaulProxyServer.Agent node : nodes) {
                    if (node.name().startsWith(prefix)) {
                        out.println(node);
                    }
                }
            } else if ("remove".equals(action)) {
                final String agentKey = args[2];
                final WebSocketBackhaulProxyServer.Agent agent = server.lookupAgent(agentKey);
                if (null == agent) {
                    out.println(String.format("Agent '%s' not exists", agentKey));
                } else {
                    agent.close();
                    out.println(String.format("Agent '%s' removed", agentKey));
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
                out.println("  alias                                 List alias for forward target hostname");
                return;
            }
            final String action = args[1];
            if ("list".equals(action)) {
                final String option = args.length > 2 ? args[2] : "";
                final boolean isL = "-l".equals(option);
                Collection<WebSocketBackhaulProxyServer.PortForwarding2> forwards = server.getAccessRules();
                for (WebSocketBackhaulProxyServer.PortForwarding2 forward : forwards) {
                    out.println(forward);
                    if (isL) {
                        for (WebSocketBackhaulProxyServer.Tunnel link : server.getConnections(forward)) {
                            out.println("  |- " + link);
                        }
                    }
                }
            } else if ("add".equals(action)) {
                final int port = Integer.parseInt(args[2]);
                final String agent = args[3];
                final String target = args[4];
                final String[] segments = target.split(":", 2);
                final String hostname = segments[0];
                final int targetPort = Integer.parseInt(segments[1]);
                String hostnameToUse = forwardHostnameAliasMap.get(hostname);
                hostnameToUse = null != hostnameToUse ? hostnameToUse : hostname;
                server.forward(port, agent, hostnameToUse, targetPort);
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
            } else if ("alias".equals(action)) {
                for (final Map.Entry<String, String> entry : forwardHostnameAliasMap.entrySet()) {
                    out.println(String.format("%s     ->    %s", entry.getKey(), entry.getValue()));
                }
            }
        } else {
            out.println(String.format("%s: command not found", args[0]));
        }
    }

    private void doExecuteAgentCommand(final List<String> args, final PrintStream out) {
        if ("list".equals(safeGet(args, 0))) {
            final Collection<Discover.Agent> agents = discover.getAgents();
            final int[] columnWidths = new int[]{"ID".length(), "NAME".length(), "VERSION".length(), "ADDRESS".length()};
            final String[][] table = new String[agents.size() + 1][];
            int i = 0;
            table[i++] = new String[]{"ID", "NAME", "VERSION", "ADDRESS"};
            for (final Discover.Agent agent : agents) {
                table[i++] = new String[]{agent.getId(), agent.getName(), agent.getVersion(), agent.getExtranet() + '/' + agent.getIntranet()};
            }
            printTable(table, out);
            return;
        }

        if ("remove".equals(safeGet(args, 0)) && null != safeGet(args, 1)) {
            // FIXME
            return;
        }

        out.println("Usage: agent COMMAND [AGENT]");
        out.println("  list    List information about registered agents");
        out.println("  remove  Remove the registered agent");
        out.println();
    }

    private void printTable(final String[][] table, final PrintStream out) {
        final String[][] tableToUse = null != table ? table : new String[0][];
        final int columns = 1 > tableToUse.length ? 0 : tableToUse[0].length;
        final int[] columnWidths = new int[columns];
        for (final String[] col : tableToUse) {
            for (int i = 0; i < columns; i++) {
                columnWidths[i] = Math.max(col[i].length(), columnWidths[i]);
            }
        }
        for (final String[] col : tableToUse) {
            for (int i = 0; i < columns; i++) {
                final int pad = 1 > i ? 0 : 5;
                out.print(lpad(col[i], columnWidths[i] + pad));
            }
            out.println();
        }
        out.println();
    }

    private String lpad(final String text, final int length) {
        final StringBuilder buff = new StringBuilder(length).append(text);
        for (int i = text.length(); i < length; i++) {
            buff.insert(0, ' ');
        }
        return buff.toString();
    }

    private void doExecuteForwardCommand(final List<String> args, final PrintStream out) {
        out.println("Usage: forward COMMAND [OPTION]");
        out.println("  list [-l]                             List information about forward rule");
        out.println("  add L_PORT AGENT R_HOST:R_PORT        Add the forward rule, mapping local L_PORT to remote host R_HOST and port R_PORT by AGENT");
        out.println("  remove [L_PORT]                       Remove the forward rule");
        out.println("  kill [LINK_ID]                        Kill the forward link");
        out.println("  alias                                 List alias for forward target hostname");
        out.println();
    }

    private String safeGet(final List<String> args, final int index) {
        return -1 < index && index < args.size() ? args.get(index) : null;
    }

    /*-
        agents     List agents
        ps          List agent streams
        kill        Kill one or more running agent streams

        listen      Listen host port and forward to target by agent
        rm          Remove one or more port mappings
     */

    private String[] tokenize(final String line) {
        final Matcher matcher = ARGS_PATTERN.matcher(line);
        final List<String> args = new LinkedList<String>();
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
//        final WebSocketBackhaulProxyServer server = new WebSocketBackhaulProxyServer("0.0.0.0", 2345, "/tunnel", false);
//        server.start();
//        new Shell(server, new GenericLineReader(System.in, System.out), System.out, null).run();
        Discover mock = Discover.mock();
        Shell shell = new Shell(null, null, null, null);
        shell.discover = mock;
        shell.doExecuteAgentCommand(Arrays.asList("list"), System.out);
    }
}