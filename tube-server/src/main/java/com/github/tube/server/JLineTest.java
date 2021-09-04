package com.github.tube.server;

import com.github.tube.server.shell.ConsoleShell;
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



        final ConsoleShell shell = new ConsoleShell(System.in, System.out, terminal);
        shell.out.println();
        shell.out.println("Welcome to WebSocket Tunnel Service!");
        shell.out.println();
        shell.out.flush();

        while (shell.next()) {

        }
    }
}
