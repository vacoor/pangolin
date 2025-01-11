package com.github.pangolin.routing.beta.tun.tun2socks;

import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.windows.win32.WindowsNetworkInterfaceEx;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;

public class WindowsTun2Socks extends AbstractTun2Socks {
    private final String device;
    private final String proxy;
    private final String bindInterface;

    public WindowsTun2Socks(final String device, final String proxy, final String bindInterface) {
        this.device = device;
        this.proxy = proxy;
        this.bindInterface = bindInterface;
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
                // "-device", "tun://iTun?guid={1999b35f-70e1-45e9-ad0f-29eb0e06ee2b}",
//                "-proxy", "socks5://127.0.0.1:3081",
//                "-interface", "以太网 2"
                "-device", String.format("tun://%s", device),
                "-proxy", String.format("socks5://%s", proxy),
                "-interface", bindInterface
        ).directory(executable.getParentFile());
    }

    @Override
    protected void onReady() throws IOException, InterruptedException {
        // Add Interface address
        final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByAlias(getDevice());
        nix.addInterfaceAddress(InterfaceAddressEx.of("198.18.0.1", (short) 24));

        // Set Interface DNS
        nix.setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        // Flush DNS cache.
        WindowsNetworkInterfaceEx.flushDnsCache();
    }

}