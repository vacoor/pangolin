package com.github.pangolin.server.shell;

import jline.console.completer.Completer;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

import static jline.internal.Preconditions.checkNotNull;

public class LazyStringsCompleter implements Completer {
    private final Supplier<Collection<String>> supplier;

    public LazyStringsCompleter(final Supplier<Collection<String>> supplier) {
        this.supplier = supplier;
    }

    public Collection<String> getStrings() {
        return supplier.get();
    }

    @Override
    public int complete(final String buffer, final int cursor, final List<CharSequence> candidates) {
        checkNotNull(candidates);

        if (null == buffer) {
            candidates.addAll(supplier.get());
        } else {
            final TreeSet<String> strings = new TreeSet<>(supplier.get());
            for (String match : strings) {
                if (match.startsWith(buffer)) {
                    candidates.add(match);
                }
            }
        }
        return candidates.isEmpty() ? -1 : 0;
    }
}