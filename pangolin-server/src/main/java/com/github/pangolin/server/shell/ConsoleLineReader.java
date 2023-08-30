package com.github.pangolin.server.shell;

import com.github.pangolin.server.WebSocketBackhaulProxyServer;
import jline.Terminal;
import jline.console.ConsoleReader;
import jline.console.completer.AggregateCompleter;
import jline.console.completer.ArgumentCompleter;
import jline.console.completer.CandidateListCompletionHandler;
import jline.console.completer.CompletionHandler;
import jline.console.completer.NullCompleter;
import jline.console.completer.StringsCompleter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.function.Supplier;

public class ConsoleLineReader implements LineReader {
    protected final InputStream in;
    protected final OutputStream out;
    private final ConsoleReader console;

    public ConsoleLineReader(final InputStream consoleIn, final OutputStream consoleOut, final Terminal terminal, final Supplier<Collection<String>> agentSupplier) throws IOException {
        this.in = consoleIn;
        this.out = consoleOut;
        this.console = create(consoleIn, consoleOut, terminal, agentSupplier);
    }

    private ConsoleReader create(final InputStream consoleIn, final OutputStream consoleOut, final Terminal terminal, final Supplier<Collection<String>> agentNamesSupplier) throws IOException {
        final ConsoleReader console = new ConsoleReader(consoleIn, consoleOut, terminal);
        final CompletionHandler completionHandler = console.getCompletionHandler();
        if (completionHandler instanceof CandidateListCompletionHandler) {
            final CandidateListCompletionHandler candidateListCompletionHandler = (CandidateListCompletionHandler) completionHandler;
            candidateListCompletionHandler.setStripAnsi(true);
            candidateListCompletionHandler.setPrintSpaceAfterFullCompletion(false);
        }
        console.setExpandEvents(false);

        final AggregateCompleter completer = new AggregateCompleter();
        final LazyStringsCompleter agentCompleter = new LazyStringsCompleter(agentNamesSupplier);
        completer.getCompleters().add(new StringsCompleter("exit"));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("agent"), new StringsCompleter("list"), NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("agent"), new StringsCompleter("remove"), agentCompleter, NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("list"), NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("add"), NullCompleter.INSTANCE, agentCompleter, NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("remove"), NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("kill"), NullCompleter.INSTANCE));
        console.addCompleter(completer);

        return console;
    }

    protected String getPrompt() {
        return "# ";
    }

    @Override
    public String readLine() throws IOException {
        return console.readLine(getPrompt());
    }

    @Override
    public void close() throws IOException {
        console.close();
    }
}