package com.github.tube.server.shell;

import jline.Terminal;
import jline.TerminalFactory;
import jline.internal.InputStreamReader;

import java.io.OutputStreamWriter;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20210903
 */
public class JLineTest {

    public static void main(String[] args) throws Exception {
        // final Terminal terminal = new WindowsTerminal();
        // TerminalFactory.registerFlavor(TerminalFactory.Flavor.WINDOWS, jline.UnsupportedTerminal.class);
        final Terminal terminal = TerminalFactory.create();
//        final LineReader reader = new ConsoleLineReader(System.in, System.out, terminal);
        final LineReader reader = new GenericLineReader(System.in, System.out);
        final WebSocketTunnelShell shell = new WebSocketTunnelShell(reader, System.out);
        shell.run();
    }
}
