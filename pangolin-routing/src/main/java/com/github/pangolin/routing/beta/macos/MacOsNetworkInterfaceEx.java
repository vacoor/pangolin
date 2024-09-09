package com.github.pangolin.routing.beta.macos;

import static com.github.pangolin.routing.beta.If.IFNAMSIZ;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.drasyl.channel.tun.jna.shared.LibC.*;
import static com.github.pangolin.routing.beta.macos.Sockio.*;
import static org.pcap4j.core.Inets.AF_INET;

import com.github.pangolin.routing.beta.If.Ifreq;
import com.github.pangolin.routing.beta.If.sockaddr_in;
import com.github.pangolin.routing.beta.If.sockaddr_in6;
import com.sun.jna.Structure;
import java.net.Inet4Address;

/**
 */
public class MacOsNetworkInterfaceEx {

  private static int getMTU(final int fd, final String ifname) {
    final Ifreq ifr = new Ifreq(ifname);
    ioctl(fd, SIOCGIFMTU, ifr);
    return ifr.ifr_ifru.ifru_mtu;
  }

  private static void setMTU(final int fd, final String ifname, final int mtu) {
    final Ifreq ifr = new Ifreq(ifname);
    ifr.ifr_ifru.setType("ifru_mtu");
    ifr.ifr_ifru.ifru_mtu = mtu;
    ioctl(fd, SIOCSIFMTU, ifr);
  }


  private static void setInterfaceAddress(final int fd, final String ifname, final Inet4Address address) {
      final ifaliasreq ifra = new ifaliasreq(ifname);
      ifra.ifra_addr.sin_family = AF_INET;
      ifra.ifra_addr.sin_port = 0;
      ifra.ifra_addr.sin_addr = address.getAddress();

    ifra.ifra_mask.sin_family = AF_INET;
    ifra.ifra_mask.sin_port = 0;
    ifra.ifra_mask.sin_addr = getNetworkAddress(address.getAddress(), 24);

    ioctl(fd, SIOCSIFADDR, ifra);
  }

  private static byte[] getNetworkAddress(final byte[] ipBytes, int cidrPrefix) {
    final byte[] networkBytes = new byte[ipBytes.length];
    for (int i = 0, prefix = cidrPrefix; i < ipBytes.length && prefix > 0; i++, prefix -= 8) {
      byte b = ipBytes[i];
      if (prefix < 8) {
        final int shift = 8 - prefix;
        b = (byte) ((b >> shift) << shift);
      }
      networkBytes[i] = b;
    }
    return networkBytes;
  }

  @Structure.FieldOrder({ "ifra_name", "ifra_addr", "ifra_broadaddr", "ifra_mask" })
  public static class ifaliasreq extends Structure {
    public byte[] ifra_name = new byte[IFNAMSIZ];
    public sockaddr_in ifra_addr;
    public sockaddr_in ifra_broadaddr;
    public sockaddr_in ifra_mask;

    public ifaliasreq(final String name) {
      this.ifra_name = new byte[IFNAMSIZ];
      if (name != null) {
        final byte[] bytes = name.getBytes(US_ASCII);
        System.arraycopy(bytes, 0, this.ifra_name, 0, bytes.length);
      }
    }
  }


  @Structure.FieldOrder({ "ifra_name", "ifra_addr", "ifra_dstaddr", "ifra_prefixmask", "ifra_flags" })
  public static class in6_aliasreq {
    public byte[] ifra_name = new byte[IFNAMSIZ];
    public sockaddr_in6 ifra_addr;
    public sockaddr_in6 ifra_dstaddr;
    public sockaddr_in6 ifra_prefixmask;
    public int ifra_flags;
//    public ifra_lifetime;

  }

}
