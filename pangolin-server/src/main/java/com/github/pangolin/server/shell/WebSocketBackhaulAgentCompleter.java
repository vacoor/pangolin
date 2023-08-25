package com.github.pangolin.server.shell;

import com.github.pangolin.server.WebSocketBackhaullProxyServer;
import jline.console.completer.Completer;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class WebSocketBackhaulAgentCompleter implements Completer {
    private final WebSocketBackhaullProxyServer server;

    public WebSocketBackhaulAgentCompleter(final WebSocketBackhaullProxyServer server) {
        this.server = server;
    }

    @Override
    public int complete(final String buffer, final int cursor, final List<CharSequence> candidates) {
        final Set<String> names = new TreeSet<>();
        for (WebSocketBackhaullProxyServer.Agent node : server.getBrokers()) {
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