package com.github.tube.server.shell;

import com.github.tube.server.WebSocketTunnelServer;
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
import java.io.PrintStream;

public class ConsoleLineReader implements LineReader {
    protected final InputStream in;
    protected final OutputStream out;
    private final ConsoleReader console;

    public ConsoleLineReader(final WebSocketTunnelServer server, final InputStream in, final OutputStream out, final Terminal terminal) throws IOException {
        this.in = in;
        this.out = out;
        this.console = create(server, in, out, terminal);
    }

    protected ConsoleReader create(final WebSocketTunnelServer server, final InputStream in, final OutputStream out, final Terminal terminal) throws IOException {
        final ConsoleReader console = new ConsoleReader(in, out, terminal);
        final CompletionHandler completionHandler = console.getCompletionHandler();
        if (completionHandler instanceof CandidateListCompletionHandler) {
            final CandidateListCompletionHandler candidateListCompletionHandler = (CandidateListCompletionHandler) completionHandler;
            candidateListCompletionHandler.setStripAnsi(true);
            candidateListCompletionHandler.setPrintSpaceAfterFullCompletion(false);
        }
        console.setExpandEvents(false);

        final TunnelCompleter tunnelCompleter = new TunnelCompleter(server);
        /*-
         * forward
         */

        final AggregateCompleter completer = new AggregateCompleter();
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("tunnel"), new StringsCompleter("list"), NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("tunnel"), new StringsCompleter("remove"), NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("add"), tunnelCompleter, NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("remove"), NullCompleter.INSTANCE));
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