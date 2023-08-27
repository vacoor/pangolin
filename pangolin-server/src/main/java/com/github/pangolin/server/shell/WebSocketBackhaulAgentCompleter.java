package com.github.pangolin.server.shell;

import com.github.pangolin.server.WebSocketBackhaulProxyServer;
import jline.console.completer.Completer;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class WebSocketBackhaulAgentCompleter implements Completer {
    private final WebSocketBackhaulProxyServer server;

    public WebSocketBackhaulAgentCompleter(final WebSocketBackhaulProxyServer server) {
        this.server = server;
    }

    @Override
    public int complete(final String buffer, final int cursor, final List<CharSequence> candidates) {
        final Set<String> names = new TreeSet<>();
        for (WebSocketBackhaulProxyServer.Agent node : server.getBrokers()) {
            names.add(node.name());
        }

        if (null == buffer) {
            candidates.addAll(names);
            return 0;
        }
        for (String string : names) {
            if (string.startsWith(buffer)) {
                candidates.add(string);
            }
        }
        return candidates.isEmpty() ? -1 : 0;
    }
}