package com.github.pangolin.tun;

import com.github.pangolin.tun.beta.TunTest2;
import com.github.pangolin.tun.net.AbstractTunAdapter;
import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.TunAdapter;
import com.github.pangolin.tun.net.darwin.DarwinTunAdapter;
import com.github.pangolin.tun.net.linux.LinuxTunAdapter;
import com.github.pangolin.tun.net.windows.WindowsTunAdapter;
import io.netty.util.internal.PlatformDependent;
import org.pcap4j.packet.IpSelector;
import org.pcap4j.packet.Packet;

public class Main {
    public static void main(String[] args) throws Exception {
        if (true) {
            TunTest2.main(args);
            return;
        }

        TunAdapter device;
        final int mtu = 0;
        final String ifname = args.length > 0 ? args[0] : "utun8";
        if (PlatformDependent.isOsx()) {
            device = DarwinTunAdapter.open(ifname, mtu);
        }
        else if (PlatformDependent.isWindows()) {
            device = WindowsTunAdapter.open(ifname, "P", mtu);
        }
        else {
            device = LinuxTunAdapter.open(ifname, mtu);
        }

        ((AbstractTunAdapter) device).setInterfaceAddress(InterfaceAddressEx.of("192.168.3.1", 24));

        while (true) {
            final byte[] bytes = ((AbstractTunAdapter) device).readBytes();
            Packet packet = IpSelector.newPacket(bytes, 0, bytes.length);
            System.out.println(packet);
        }
    }
}
