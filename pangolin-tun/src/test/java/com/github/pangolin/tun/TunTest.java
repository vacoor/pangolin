package com.github.pangolin.tun;

import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.windows.WindowsNetworkInterfaceEx;
import com.sun.jna.WString;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.jna.windows.WindowsTunDevice;

import java.lang.reflect.Field;
import java.net.InetAddress;

/**
 *
 */
@Slf4j
public class TunTest {

    public static void main(String[] args) throws Exception {
        /*
      Runtime.getRuntime().addShutdownHook(new Thread() {
          @Override
          public void run() {
            System.out.println("Bye");
          }
      });
        TimeUnit.SECONDS.sleep(1000);
        System.exit(0);
        */

        final Field innerString = WString.class.getDeclaredField("string");
        innerString.setAccessible(true);
        innerString.set(WindowsTunDevice.TUNNEL_TYPE, "PAN");

        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            final Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) throws Exception {
                            ch.pipeline().addLast(new IpPacketDecoder());
                            ch.pipeline().addLast(new TcpPacketHandler());
                        }
                    });
            final Channel ch = b.bind(new TunAddress("iTCP")).sync().channel();
            // int code = new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "address", "name=\"utun99\"", "source=static", "address=192.168.1.1", "mask=255.255.255.0").start().waitFor();
            // send/receive messages of type TunPacket...
            WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByAlias("iTCP");
            nix.setInterfaceAddress(InterfaceAddressEx.of(InetAddress.getByName("192.168.1.1"), (short) 24));
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }

//        final PcapNetworkInterface nif = Pcaps.getDevByName("en0");
//        final PcapHandle handle = nif.openLive(65536, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 10);
    }
}
