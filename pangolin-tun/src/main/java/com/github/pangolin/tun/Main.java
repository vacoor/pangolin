package com.github.pangolin.tun;

import com.github.pangolin.tun.beta.TunTest2;
import com.github.pangolin.tun.net.darwin.DarwinTunAdapter;
import com.github.pangolin.tun.net.linux.LinuxNetworkInterfaceEx;
import com.github.pangolin.tun.net.linux.LinuxTunAdapter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.drasyl.channel.tun.jna.TunDevice;
import org.drasyl.channel.tun.jna.darwin.DarwinTunDevice;
import org.drasyl.channel.tun.jna.linux.LinuxTunDevice;
import org.pcap4j.packet.IpSelector;
import org.pcap4j.packet.Packet;

import java.net.Inet4Address;
import java.net.InetAddress;

public class Main {
    public static void main(String[] args) throws Exception {
        TunTest2.main(args);
//        LinuxTunAdapter.main(args);
//        DarwinTunAdapter.main(args);
        /*
        TunDevice tun = LinuxTunDevice.open("tun9", 1500);
        String ifname = tun.localAddress().ifName();

        LinuxNetworkInterfaceEx nix = new LinuxNetworkInterfaceEx(ifname);
        nix.setInterfaceAddress4((Inet4Address) InetAddress.getByName("192.168.3.1"), 24);

        UnpooledByteBufAllocator alloc = new UnpooledByteBufAllocator(true);
        while (true) {
            final byte[] bytes = ByteBufUtil.getBytes(tun.readPacket(alloc).content());
            Packet packet = IpSelector.newPacket(bytes, 0, bytes.length);
            System.out.println(packet);
        }
        */
    }
}
