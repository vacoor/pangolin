package com.github.tube.server.shell;

import com.github.tube.server.shell.AbstractShell;
import jline.Terminal;
import jline.console.ConsoleReader;
import jline.console.completer.AggregateCompleter;
import jline.console.completer.CandidateListCompletionHandler;
import jline.console.completer.CompletionHandler;
import jline.console.completer.StringsCompleter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public class ConsoleShell extends AbstractShell {
    private final InputStream in;
    private final PrintStream out;
    private final ConsoleReader console;

    public ConsoleShell(final InputStream in, final PrintStream out, final Terminal terminal) throws IOException {
        this.in = in;
        this.out = out;
        this.console = create(in, out, terminal);
    }

    protected ConsoleReader create(final InputStream in, final OutputStream out, final Terminal terminal) throws IOException {
        final ConsoleReader console = new ConsoleReader(in, out, terminal);
        final CompletionHandler completionHandler = console.getCompletionHandler();
        if (completionHandler instanceof CandidateListCompletionHandler) {
            final CandidateListCompletionHandler candidateListCompletionHandler = (CandidateListCompletionHandler) completionHandler;
            candidateListCompletionHandler.setStripAnsi(true);
            candidateListCompletionHandler.setPrintSpaceAfterFullCompletion(false);
        }
        console.setExpandEvents(false);

        final AggregateCompleter completer = new AggregateCompleter();
        completer.getCompleters().add(new StringsCompleter("tunnel "));
        completer.getCompleters().add(new StringsCompleter("tunnel2 "));
        completer.getCompleters().add(new StringsCompleter("tunnel3 "));

        console.addCompleter(completer);
        return console;
    }

    protected String getPrompt() {
        return "tunnel# ";
    }

    @Override
    protected String readLine() throws IOException {
        return console.readLine(getPrompt());
    }

    @Override
    protected PrintStream getOut() throws IOException {
        return out;
    }
}