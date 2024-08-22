package com.github.pangolin.routing.beta.tun.tun2socks;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class DarwinTun2Socks extends AbstractTun2Socks {

    @Override
    protected String tun2socksReadyPattern() {
        return null;
    }

    @Override
    protected ProcessBuilder createProcessBuilder() throws Exception {
        final URL resource = getClass().getResource("/META-INF/native/tun2socks/darwin/tun2socks-darwin-amd64-3");
        final File executable = new File(resource.toURI().getPath());
        /*-
         *
         * tun2socks.exe -device wintun -proxy socks5://127.0.0.1:3081 -interface "以太网 2"
         */
        return new ProcessBuilder()
                .command(
                        executable.getAbsolutePath(),
                        "-device", "utun123",
                        "-proxy", "socks5://127.0.0.1:2081",
                        "-interface", "en0"
                )
                .directory(executable.getParentFile());
    }

    @Override
    protected void customimeReady() throws IOException, InterruptedException {
    }

    public static void main(String[] args) {

    }
}