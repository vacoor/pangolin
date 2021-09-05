package com.github.tube.server.shell;

import com.github.tube.server.shell.LineReader;
import com.github.tube.server.shell.TunnelCompleter;
import jline.Terminal;
import jline.console.ConsoleReader;
import jline.console.completer.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public class ConsoleLineReader implements LineReader {
    protected final InputStream in;
    protected final PrintStream out;
    private final ConsoleReader console;

    public ConsoleLineReader(final InputStream in, final PrintStream out, final Terminal terminal) throws IOException {
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
        completer.getCompleters().add(new ArgumentCompleter(
                new StringsCompleter("forward"),
                new TunnelCompleter(),
                NullCompleter.INSTANCE
        ));


        console.addCompleter(completer);
        return console;
    }

    protected String getPrompt() {
        return "tunnel# ";
    }

    @Override
    public String readLine() throws IOException {
        return console.readLine(getPrompt());
    }
}