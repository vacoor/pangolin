package com.github.pangolin.server.mgt.shell;

import com.github.pangolin.server.WebSocketBridgeServerEngine;
import com.github.pangolin.server.WebSocketBridgeServerForwarder;
import com.google.common.collect.Lists;
import io.netty.channel.nio.NioEventLoopGroup;
import jline.UnsupportedTerminal;
import jline.console.ConsoleReader;
import lombok.extern.slf4j.Slf4j;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class WebSocketBridgeServerShell {
    private static final Pattern ARGS_PATTERN = Pattern.compile("\\s*([^\"\']\\S*|\"[^\"]*\"|'[^']*')\\s*");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("^([\'\"])(.*)(\\1)$");

    private final boolean breakOnNull;
    private final ConsoleReader console;
    private final WebSocketBridgeServerEngine webSocketBridgeServerEngine;
    private final WebSocketBridgeServerForwarder forwarder;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private WebSocketBridgeServerShell(final ConsoleReader console, final boolean breakOnNull, final WebSocketBridgeServerEngine webSocketBridgeServerEngine, final WebSocketBridgeServerForwarder forwarder) {
        this.console = console;
        this.breakOnNull = breakOnNull;
        this.webSocketBridgeServerEngine = webSocketBridgeServerEngine;
        this.forwarder = forwarder;
    }

    public void start() {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WebSocketBridgeServerShell.this.run();
                } catch (final IOException e) {
                    log.error("Shell Error: {}", e.getMessage(), e);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void run() throws IOException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        console.println();
        console.println("Welcome to Pangolin!");
        console.println();
        console.flush();
        while (started.get() && next()) ;
    }

    private boolean next() throws IOException {
        final String line = console.readLine("# ");
        if (null == line && breakOnNull) {
            return false;
        }
        execute(null != line ? line.trim() : "");
        return true;
    }

    protected void execute(final String line) throws IOException {
        try {
            doExecute(line, console);
        } catch (final Throwable ex) {
            console.print("Error: ");
            console.println(ex.getMessage());
        }
    }

    private void doExecute(final String line, final ConsoleReader out) throws Exception {
        // find command and execute
        final String[] cmdline = tokenize(line);
        if (0 == cmdline.length) {
            out.println("agent       Agents operations");
            out.println("forward     Port forwarding operations");
            out.println();
            return;
        }
        final String command = cmdline[0];
        final List<String> args = Arrays.asList(cmdline).subList(1, cmdline.length);
        if ("agent".equals(command)) {
            doExecuteAgentCommand(args, out);
            return;
        }
        if ("forward".equals(command)) {
            doExecuteForwardCommand(args, out);
            return;
        }
        if ("connection".equals(command)) {
            doExecuteConnectionCommand(args, out);
            return;
        }
        if ("exit".equals(command) || "quit".equals(command)) {
            started.set(false);
            out.println("Bye");
            console.close();
            console.getOutput().close();
            return;
        }
        out.println(String.format("%s: command not found", command));
    }

    private void doExecuteAgentCommand(final List<String> args, final ConsoleReader out) throws IOException {
        if ("list".equals(safeGet(args, 0))) {
            final Collection<WebSocketBridgeServerEngine.Agent> agents = getAgents();
            final String[][] table = new String[agents.size() + 1][];
            int i = 0;
            table[i++] = new String[]{"ID", "NAME", "VERSION", "ADDRESS"};
            for (final WebSocketBridgeServerEngine.Agent agent : agents) {
                table[i++] = new String[]{agent.getId(), agent.getName(), agent.getVersion(), agent.getExtranet() + '/' + agent.getIntranet()};
            }
            printTable(table, out);
            return;
        }

        if ("remove".equals(safeGet(args, 0)) && null != safeGet(args, 1)) {
            final List<String[]> table = Lists.newArrayList();
            table.add(new String[]{"AGENT", "RESULT"});
            for (final String agentKey : args.subList(1, args.size())) {
                try {
                    if (removeAgent(agentKey)) {
                        table.add(new String[]{agentKey, "Removed"});
                    } else {
                        table.add(new String[]{agentKey, "Not found"});
                    }
                } catch (final Exception e) {
                    table.add(new String[]{agentKey, "Error: " + e.getMessage()});
                }
            }
            printTable(table.toArray(new String[table.size()][]), out);
            return;
        }

        out.println("Usage: agent COMMAND [AGENT..]");
        out.println("  list    List information about registered agents");
        out.println("  remove  Remove the registered agent");
        out.println();
    }

    private void doExecuteForwardCommand(final List<String> args, final ConsoleReader out) throws InterruptedException, IOException {
        if ("list".equals(safeGet(args, 0))) {
            final Collection<WebSocketBridgeServerForwarder.Forwarding> forwardings = getForwardings();
            final String[][] table = new String[forwardings.size() + 1][];
            int i = 0;
            table[i++] = new String[]{"SOURCE", "AGENT", "DESTINATION"};
            for (final WebSocketBridgeServerForwarder.Forwarding forwarding : forwardings) {
                table[i++] = new String[]{forwarding.getLocalAddr().toString(), forwarding.getAgentKey(), forwarding.getRemoteAddr().toString()};
            }
            printTable(table, out);
            return;
        }

        if ("add".equals(safeGet(args, 0))) {
            final String rule = safeGet(args, 1);
            final String[] segments = null != rule ? rule.split(":") : null;
            if (null != segments && segments.length == 4) {
                final String localPortStr = segments[0];
                final String agentKey = segments[1];
                final String remoteAddr = segments[2];
                final String remotePortStr = segments[3];
                final int localPort = Integer.parseInt(localPortStr);
                final int remotePort = Integer.parseInt(remotePortStr);
                addForwarding(localPort, agentKey, remoteAddr, remotePort);
                out.println("OK");
                return;
            }
        }

        if ("remove".equals(safeGet(args, 0))) {
            final String localPortStr = safeGet(args, 1);
            if (null != localPortStr) {
                final int localPort = Integer.parseInt(localPortStr);
                removeForwarding(localPort);
                out.println("OK");
                return;
            }
        }

        out.println("Usage: forward COMMAND [OPTION]");
        out.println("  list                                  List information about forward rule");
        out.println("  add    local_port:agent_key:remote_host:remote_port\r\n" +
                "         Add the forward rule, mapping local L_PORT to remote host R_HOST and port R_PORT by AGENT");
        out.println("  remove local_port                     Remove the forward rule");
        out.println("  alias                                 List alias for forward target hostname");
        out.println();
    }

    private void doExecuteConnectionCommand(final List<String> args, final ConsoleReader out) throws IOException {
        if ("list".equals(safeGet(args, 0))) {
            final Collection<WebSocketBridgeServerEngine.Connection> connections = webSocketBridgeServerEngine.getConnections();
            final String[][] table = new String[connections.size() + 1][];
            int i = 0;
            table[i++] = new String[]{"ID", "CONNECTION", "STATE"};
            for (final WebSocketBridgeServerEngine.Connection conn : connections) {
                final WebSocketBridgeServerEngine.Agent a = conn.getAgent();
                final String desc = conn.getSource() + " -" + a.getName() + "(" + a.getExtranet() + "/" + a.getIntranet() + ")" + "-> " + conn.getTarget();
                table[i++] = new String[]{conn.getId(), desc, conn.getHandshakePromise().isSuccess() ? "ESTABLISHED" : "HANDSHAKING"};
            }
            printTable(table, out);
            return;
        }

        if ("kill".equals(safeGet(args, 0)) && null != safeGet(args, 1)) {
            final List<String[]> table = Lists.newArrayList();
            table.add(new String[]{"CONNECTION", "RESULT"});
            for (final String connectionId : args.subList(1, args.size())) {
                try {
                    if (webSocketBridgeServerEngine.kill(connectionId)) {
                        table.add(new String[]{connectionId, "Killed"});
                    } else {
                        table.add(new String[]{connectionId, "Not found"});
                    }
                } catch (final Exception e) {
                    table.add(new String[]{connectionId, "Error: " + e.getMessage()});
                }
            }
            printTable(table.toArray(new String[table.size()][]), out);
            return;
        }

        out.println("Usage: connection COMMAND [args..]");
        out.println("  list                 List information about connections");
        out.println("  kill connection_id   Kill the connection");
        out.println();
    }


    private Collection<WebSocketBridgeServerEngine.Agent> getAgents() {
        return webSocketBridgeServerEngine.getAgents();
    }

    private boolean removeAgent(final String agentKey) throws InterruptedException {
        final Collection<WebSocketBridgeServerEngine.Agent> agents = webSocketBridgeServerEngine.getAgents();
        final Iterator<WebSocketBridgeServerEngine.Agent> it = agents.iterator();
        while (it.hasNext()) {
            final WebSocketBridgeServerEngine.Agent agent = it.next();
            if (agent.getId().equals(agentKey)) {
                agent.getBus().close().sync();
                return true;
            }
        }
        return false;
    }

    private Collection<WebSocketBridgeServerForwarder.Forwarding> getForwardings() {
        return forwarder.getForwardings();
    }

    private void addForwarding(final int localPort, final String agentKey,
                               final String remoteAddr, final int remotePort) throws InterruptedException {
        forwarder.addForwarding(localPort, agentKey, InetSocketAddress.createUnresolved(remoteAddr, remotePort));
    }

    private void removeForwarding(final int port) {
        forwarder.removeForwarding(port);
    }


    private String safeGet(final List<String> args, final int index) {
        return -1 < index && index < args.size() ? args.get(index) : null;
    }

    private void printTable(final String[][] table, final ConsoleReader out) throws IOException {
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

    public static WebSocketBridgeServerShell create(final ConsoleReader console, final boolean breakOnNull, final WebSocketBridgeServerEngine webSocketBridgeServerEngine, final WebSocketBridgeServerForwarder forwarder) {
        return new WebSocketBridgeServerShell(console, breakOnNull, webSocketBridgeServerEngine, forwarder);
    }

    public static void main(String[] args) throws IOException {
        final WebSocketBridgeServerEngine webSocketBridgeServerEngine = new WebSocketBridgeServerEngine();
        final NioEventLoopGroup bossGroup = new NioEventLoopGroup(2);
        final NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        final WebSocketBridgeServerForwarder forwarder = new WebSocketBridgeServerForwarder(webSocketBridgeServerEngine, bossGroup, workerGroup);
        final ConsoleReader console = ConsoleReaderFactory.newConsoleReader(
                new FileInputStream(FileDescriptor.in), System.out,
                new UnsupportedTerminal(false, false),
                () -> webSocketBridgeServerEngine.getAgents().stream().map(WebSocketBridgeServerEngine.Agent::getName).collect(Collectors.toSet()),
                () -> webSocketBridgeServerEngine.getConnections().stream().map(WebSocketBridgeServerEngine.Connection::getId).collect(Collectors.toSet())
        );
        WebSocketBridgeServerShell.create(console, true, webSocketBridgeServerEngine, forwarder).start();
    }
}