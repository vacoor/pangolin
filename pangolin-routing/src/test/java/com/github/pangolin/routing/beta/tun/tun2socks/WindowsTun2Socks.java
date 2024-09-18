package com.github.pangolin.routing.beta.tun.tun2socks;

import com.github.pangolin.routing.beta.tun.net.InterfaceAddressEx;
import com.github.pangolin.routing.beta.tun.net.windows.win32.WindowsNetworkInterfaceEx;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
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
        final URL resource = getClass().getResource("/META-INF/native/tun2socks/windows/amd64/tun2socks.exe");
        final File executable = new File(resource.toURI().getPath());
        /*-
         * tun2socks.exe -device iTun -proxy socks5://127.0.0.1:3081 -interface "以太网 2"
         */
        return new ProcessBuilder().command(
                executable.getAbsolutePath(),
                "-device", "tun://iTun?guid={1999b35f-70e1-45e9-ad0f-29eb0e06ee2b}",
                "-proxy", "socks5://127.0.0.1:3081",
                "-interface", "以太网 2"
        ).directory(executable.getParentFile());
    }

    @Override
    protected void onReady() throws IOException, InterruptedException {
        // Add Interface address
        final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByAlias("iTun");
        nix.addInterfaceAddress(InterfaceAddressEx.of("198.18.0.1", (short) 24));

        // Set Interface DNS
        nix.setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        // Flush DNS cache.
        WindowsNetworkInterfaceEx.flushDnsCache();
    }

    public static void main(String[] args) throws Exception {
        new WindowsTun2Socks().start();
        System.out.println();
    }
}