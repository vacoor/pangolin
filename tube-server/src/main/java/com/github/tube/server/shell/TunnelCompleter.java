package com.github.tube.server.shell;

import com.github.tube.server.WebSocketTunnelServer;
import jline.console.completer.Completer;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class TunnelCompleter implements Completer {
    private final WebSocketTunnelServer server;

    public TunnelCompleter(final WebSocketTunnelServer server) {
        this.server = server;
    }

    @Override
    public int complete(final String buffer, final int cursor, final List<CharSequence> candidates) {
        System.out.println("------:" + buffer);
        Set<String> strings = new TreeSet<>(server.getTunnelNames());
        if (null == buffer) {
            candidates.addAll(strings);
            return 0;
        }
        for (String string : strings) {
            if (string.startsWith(buffer)) {
                candidates.add(string);
            }
        }
        return candidates.isEmpty() ? -1 : 0;
    }
}