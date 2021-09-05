package com.github.tube.server.shell;

import jline.Terminal;
import jline.TerminalFactory;

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
        final LineReader reader = new ConsoleLineReader(System.in, System.out, terminal);
        final WebSocketTunnelShell shell = new WebSocketTunnelShell(reader, System.out);
        shell.output.println();
        shell.output.println("Welcome to WebSocket Tunnel Service!");
        shell.output.println();
        shell.output.flush();

        while (shell.next()) {

        }
    }
}
