package com.github.pangolin.proxy.backhaul.server.shell;

import jline.Terminal;
import jline.console.ConsoleReader;
import jline.console.completer.AggregateCompleter;
import jline.console.completer.ArgumentCompleter;
import jline.console.completer.CandidateListCompletionHandler;
import jline.console.completer.Completer;
import jline.console.completer.CompletionHandler;
import jline.console.completer.NullCompleter;
import jline.console.completer.StringsCompleter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

import static jline.internal.Preconditions.checkNotNull;

/**
 *
 */
public abstract class ConsoleReaderFactory {

    private ConsoleReaderFactory() {
    }

    public static ConsoleReader newConsoleReader(final InputStream consoleIn, final OutputStream consoleOut,
                                                 final Terminal terminal, final Supplier<Collection<String>> agentNames) throws IOException {
        final ConsoleReader console = new ConsoleReader(consoleIn, consoleOut, terminal);
        console.addCompleter(createCompleter(agentNames));
        console.setCompletionHandler(createCompletionHandler());
        return console;
    }

    private static CompletionHandler createCompletionHandler() {
        final CandidateListCompletionHandler completion = new CandidateListCompletionHandler();
        completion.setStripAnsi(true);
        completion.setPrintSpaceAfterFullCompletion(true);
        return completion;
    }

    private static Completer createCompleter(final Supplier<Collection<String>> agentNames) {
        return new AggregateCompleter(
                new StringsCompleter("exit"),
                new ArgumentCompleter(new StringsCompleter("agent"), new StringsCompleter("list"), NullCompleter.INSTANCE),
                new ArgumentCompleter(new StringsCompleter("agent"), new StringsCompleter("remove"), new LazyStringsCompleter(agentNames), NullCompleter.INSTANCE),
                new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("list"), NullCompleter.INSTANCE),
                new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("add"), NullCompleter.INSTANCE, new LazyStringsCompleter(agentNames), NullCompleter.INSTANCE),
                new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("remove"), NullCompleter.INSTANCE),
                new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("kill"), NullCompleter.INSTANCE)
        );
    }

    private static class LazyStringsCompleter implements Completer {
        private final Supplier<Collection<String>> supplier;

        LazyStringsCompleter(final Supplier<Collection<String>> supplier) {
            this.supplier = supplier;
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

}
