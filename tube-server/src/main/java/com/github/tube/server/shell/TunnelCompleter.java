package com.github.tube.server.shell;

import jline.console.completer.Completer;

import java.util.Arrays;
import java.util.List;

public class TunnelCompleter implements Completer {
    @Override
    public int complete(final String buffer, final int cursor, final List<CharSequence> candidates) {
        List<String> strings = Arrays.asList("default", "default2", "test");
        if (null == buffer) {
            candidates.add("default");
            candidates.add("default2");
            candidates.add("test");
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