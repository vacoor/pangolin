package com.github.pangolin.server.shell;

import com.github.pangolin.server.WebSocketTunnelServer;
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

        final WebSocketBrokerCompleter webSocketBrokerCompleter = new WebSocketBrokerCompleter(server);
        /*-
         * forward
         */

        final AggregateCompleter completer = new AggregateCompleter();
        completer.getCompleters().add(new StringsCompleter("exit"));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("broker"), new StringsCompleter("list"), NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("broker"), new StringsCompleter("remove"), webSocketBrokerCompleter, NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("list"), NullCompleter.INSTANCE));
        completer.getCompleters().add(new ArgumentCompleter(new StringsCompleter("forward"), new StringsCompleter("add"), NullCompleter.INSTANCE, webSocketBrokerCompleter, NullCompleter.INSTANCE));
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