package com.github.pangolin.routing.beta.tun.tun2socks;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

public class WindowsTun2Socks extends AbstractTun2Socks {
    private static final String TUN2SOCKS_READY_PATTERN = ".*tun://.* <-> .*";

    @Override
    protected String tun2socksReadyPattern() {
        return TUN2SOCKS_READY_PATTERN;
    }

    @Override
    protected ProcessBuilder createProcessBuilder() throws URISyntaxException {
        final URL resource = getClass().getResource("/support/windows/tun2socks.exe");
        final File executable = new File(resource.toURI().getPath());
        /*-
         *
         * tun2socks.exe -device wintun -proxy socks5://127.0.0.1:3081 -interface "以太网 2"
         */
        return new ProcessBuilder()
                .command(
                        executable.getAbsolutePath(),
                        "-device", "wintun",
                        "-proxy", "socks5://127.0.0.1:3081",
                        "-interface", "以太网 2"
                )
                .directory(executable.getParentFile());
    }

    @Override
    protected void customimeReady() throws IOException, InterruptedException {
        int code = new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "address", "name=\"wintun\"", "source=static", "address=198.18.0.1", "mask=255.255.255.0").start().waitFor();
        System.out.println("SetAddress: " + code);
        int code2 = new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "dnsservers", "name=\"wintun\"", "static", "address=127.0.0.1", "register=none", "validate=no").start().waitFor();
        System.out.println("SetDnsServer: " + code2);
        int code3 = new ProcessBuilder().command("ipconfig", "/flushdns").start().waitFor();
        System.out.println("FlushDNS: " + code3);
    }
}