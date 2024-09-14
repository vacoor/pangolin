package com.github.pangolin.routing.beta.tun.tun2socks;

import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.tun.macos.MacOsNetworkInterfaceEx;
import org.drasyl.channel.tun.jna.TunDevice;
import org.drasyl.channel.tun.jna.darwin.DarwinTunDevice;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class DarwinTun2Socks extends AbstractTun2Socks {

    @Override
    protected ProcessBuilder createProcessBuilder() throws Exception {
        final URL resource = getClass().getResource("/META-INF/native/tun2socks/darwin/tun2socks-darwin-amd64-v3");
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
    protected void onReady() throws IOException, InterruptedException {
        // Add Interface address
        final MacOsNetworkInterfaceEx nix = new MacOsNetworkInterfaceEx("utun123");
        nix.addInterfaceAddress(InterfaceAddressEx.of("198.18.0.1", (short) 24));
        // new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "address", "name=\"wintun\"", "source=static", "address=198.18.0.1", "mask=255.255.255.0").start().waitFor();

        // Set Interface DNS
        // nix.setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        int code2 = new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "dnsservers", "name=\"wintun\"", "static", "address=127.0.0.1", "register=none", "validate=no").start().waitFor();

        // Flush DNS cache.
        System.out.println("SetDnsServer: " + code2);
        int code3 = new ProcessBuilder().command("ipconfig", "/flushdns").start().waitFor();
        System.out.println("FlushDNS: " + code3);
    }

    public static void main(String[] args) throws Exception {
//        new DarwinTun2Socks().start();
//        System.out.println();
//        TunDevice utun111 = DarwinTunDevice.open("utun3", 1500);
//        System.out.println(utun111);
        URL url = DarwinTun2Socks.class.getResource("/libhev-socks5-tunnel.so");
        final String s = new File(url.toURI().getPath()).getAbsolutePath();
        System.load(s);
        System.out.println(s);
    }
}