package com.github.pangolin.server.v11.shell;

import com.github.pangolin.server.v11.WebSocketBackhaulTunnelEngine;
import com.github.pangolin.server.v11.WebSocketBackhaulTunnelForwarder;
import com.google.common.collect.Lists;
import io.netty.channel.nio.NioEventLoopGroup;
import jline.UnsupportedTerminal;
import jline.console.ConsoleReader;
import lombok.extern.slf4j.Slf4j;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class Shell {
    private static final Pattern ARGS_PATTERN = Pattern.compile("\\s*([^\"\']\\S*|\"[^\"]*\"|'[^']*')\\s*");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("^([\'\"])(.*)(\\1)$");
    private static final String CRLF = "\n\r";

    private final boolean breakOnNull;
    private final ConsoleReader console;
    private final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine;
    private final WebSocketBackhaulTunnelForwarder forwarder;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private Shell(final ConsoleReader console, final boolean breakOnNull, final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine, final WebSocketBackhaulTunnelForwarder forwarder) {
        this.console = console;
        this.breakOnNull = breakOnNull;
        this.webSocketBackhaulTunnelEngine = webSocketBackhaulTunnelEngine;
        this.forwarder = forwarder;
    }

    public void start() {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Shell.this.run();
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
            doExecute(line, new PrintWriter(console.getOutput()));
        } catch (final Throwable ex) {
            console.print("Error: ");
            console.println(ex.getMessage());
        }
    }

    private void doExecute(final String line, final PrintWriter out) throws Exception {
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
        if ("exit".equals(command) || "quit".equals(command)) {
            started.set(false);
            out.println("Exit");
            console.close();
            return;
        }
        out.println(String.format("%s: command not found", command));
    }

    private void doExecuteAgentCommand(final List<String> args, final PrintWriter out) {
        if ("list".equals(safeGet(args, 0))) {
            final Collection<WebSocketBackhaulTunnelEngine.Agent> agents = getAgents();
            final String[][] table = new String[agents.size() + 1][];
            int i = 0;
            table[i++] = new String[]{"ID", "NAME", "VERSION", "ADDRESS"};
            for (final WebSocketBackhaulTunnelEngine.Agent agent : agents) {
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
                    removeAgent(agentKey);
                    table.add(new String[]{agentKey, "Removed"});
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

    private void doExecuteForwardCommand(final List<String> args, final PrintWriter out) throws InterruptedException {
        if ("list".equals(safeGet(args, 0))) {
            final Collection<WebSocketBackhaulTunnelForwarder.Forwarding> forwardings = getForwardings();
            final String[][] table = new String[forwardings.size() + 1][];
            int i = 0;
            table[i++] = new String[]{"SOURCE", "AGENT", "DESTINATION"};
            for (final WebSocketBackhaulTunnelForwarder.Forwarding forwarding : forwardings) {
                table[i++] = new String[]{forwarding.getLocalAddr().toString(), forwarding.getAgentKey(), forwarding.getRemoteAddr().toString()};
            }
            printTable(table, out);
            return;
        }

        if ("add".equals(safeGet(args, 0))) {
            final String localPortStr = safeGet(args, 1);
            final String agentKey = safeGet(args, 2);
            final String destination = safeGet(args, 3);
            if (null != localPortStr && null != agentKey && null != destination) {
                final int localPort = Integer.parseInt(localPortStr);
                addForwarding(localPort, agentKey, destination);
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
        out.println("  add    L_PORT AGENT R_HOST:R_PORT     Add the forward rule, mapping local L_PORT to remote host R_HOST and port R_PORT by AGENT");
        out.println("  remove L_PORT                         Remove the forward rule");
        out.println("  kill   LINK_ID                        Kill the forward link");
        out.println("  alias                                 List alias for forward target hostname");
        out.println();
    }


    private Collection<WebSocketBackhaulTunnelEngine.Agent> getAgents() {
        return webSocketBackhaulTunnelEngine.getAgents();
    }

    private void removeAgent(final String agentKey) {
    }

    private Collection<WebSocketBackhaulTunnelForwarder.Forwarding> getForwardings() {
        return forwarder.getForwardings();
    }

    private void addForwarding(final int port, final String agentKey, final String destination) throws InterruptedException {
        final String[] segments = destination.split(":");
        if (2 == segments.length) {
            final int rport = Integer.parseInt(segments[1]);
            forwarder.addForwarding(port, agentKey, InetSocketAddress.createUnresolved(segments[0], rport));
            return;
        }
        throw new IllegalArgumentException(String.format("Bad local forwarding specification: '%s'", destination));
    }

    private void removeForwarding(final int port) {
        forwarder.removeForwarding(port);
    }


    private String safeGet(final List<String> args, final int index) {
        return -1 < index && index < args.size() ? args.get(index) : null;
    }

    private void printTable(final String[][] table, final PrintWriter out) {
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

    public static Shell create(final ConsoleReader console, final boolean breakOnNull, final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine, final WebSocketBackhaulTunnelForwarder forwarder) {
        return new Shell(console, breakOnNull, webSocketBackhaulTunnelEngine, forwarder);
    }

    public static void main(String[] args) throws IOException {
        final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine = new WebSocketBackhaulTunnelEngine();
        final NioEventLoopGroup bossGroup = new NioEventLoopGroup(2);
        final NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        final WebSocketBackhaulTunnelForwarder forwarder = new WebSocketBackhaulTunnelForwarder(webSocketBackhaulTunnelEngine, bossGroup, workerGroup);
        final ConsoleReader console = ConsoleReaderFactory.newConsoleReader(
                new FileInputStream(FileDescriptor.in), System.out,
                new UnsupportedTerminal(false, false),
                () -> webSocketBackhaulTunnelEngine.getAgents().stream().map(WebSocketBackhaulTunnelEngine.Agent::getName).collect(Collectors.toSet())
        );
        Shell.create(console, true, webSocketBackhaulTunnelEngine, forwarder).start();
    }
}