package com.github.pangolin.routing.beta.linux;

import static com.github.pangolin.routing.beta.linux.Socket.AF_INET;
import static com.github.pangolin.routing.beta.linux.Socket.AF_INET6;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCGIFADDR;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCGIFMTU;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCGIFNETMASK;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCSIFADDR;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCSIFNETMASK;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOGIFINDEX;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;

import com.github.pangolin.routing.beta.If;
import com.github.pangolin.routing.beta.If.Ifreq;
import com.github.pangolin.routing.beta.If.in6_ifreq;
import com.github.pangolin.routing.beta.If.sockaddr_in;
import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.NetworkInterfaceEx;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.SocketException;
import java.util.List;

/**
 *
 */
public class LinuxNetworkInterfaceEx implements NetworkInterfaceEx {

  @Override
  public List<InterfaceAddressEx> getInterfaceAddresses() {
    return null;
  }

  @Override
  public void setInterfaceAddress(InterfaceAddressEx address) {

  }

  @Override
  public void flushInterfaceAddresses() {

  }

  @Override
  public int getMTU() throws SocketException {
    /*
    final int fd = socket(AF_INET, SOCK_DGRAM, 0);
    try {
      return getMtu(fd, )
    } finally {
      close(fd);
    }
    */
    return 0;
  }

  static byte[] getIpAddress(final int fd, final String ifname) {
    final Ifreq ifr = new Ifreq(ifname);
    ifr.ifr_ifru.setType("ifru_addr");

    ioctl(fd, SIOCGIFADDR, ifr);

    final sockaddr_in addr = ifr.ifr_ifru.ifru_addr;
    assert AF_INET == addr.sin_family;
    return addr.sin_addr;
  }

  static void setIpAddress(final int fd, final String ifname, final Inet4Address addr) {
    final Ifreq ifr = new Ifreq(ifname);
    ifr.ifr_ifru.setType("ifru_addr");
    ifr.ifr_ifru.ifru_addr.sin_family = AF_INET;
    ifr.ifr_ifru.ifru_addr.sin_port = 0;
    ifr.ifr_ifru.ifru_addr.sin_addr = addr.getAddress();

    ioctl(fd, SIOCSIFADDR, ifr);
  }

  static byte[] getNetmask(final int fd, final String ifname) {
    final Ifreq ifr = new Ifreq(ifname);
    ifr.ifr_ifru.setType("ifru_netmask");

    ioctl(fd, SIOCGIFNETMASK, ifr);

    final sockaddr_in netmask = ifr.ifr_ifru.ifru_netmask;
    assert AF_INET == netmask.sin_family;
    return netmask.sin_addr;
  }

  static void setNetmask(final int fd, final String ifname, final Inet4Address addr) {
    final Ifreq ifr = new Ifreq(ifname);
    ifr.ifr_ifru.setType("ifru_netmask");
    ifr.ifr_ifru.ifru_netmask.sin_family = AF_INET;
    ifr.ifr_ifru.ifru_netmask.sin_port = 0;
    ifr.ifr_ifru.ifru_netmask.sin_addr = addr.getAddress();

    ioctl(fd, SIOCSIFNETMASK, ifr);
  }

  static int getMtu(final int fd, final String ifname) {
    final Ifreq ifr = new Ifreq(ifname);
    final int code = ioctl(fd, SIOCGIFMTU, ifr);
    if (0 != code) {
      throw new IllegalStateException("Error code: " + code);
    }
    return ifr.ifr_ifru.ifru_mtu;
  }

  static void setInterfaceAddress6(final int fd, final String ifname, final Inet6Address addr, final int prefixLength) {
    final If.Ifreq ifr = new If.Ifreq(ifname);
    ifr.ifr_ifru.setType("ifru_ifindex");
    ioctl(fd, SIOGIFINDEX, ifr);
    System.out.println("IFR index=" + ifr.ifr_ifru.ifru_ifindex);

    final in6_ifreq ifr6 = new in6_ifreq();
    ifr6.ifr6_ifindex = ifr.ifr_ifru.ifru_ifindex;

    ifr6.ifr6_addr.sin6_family = AF_INET6;
    ifr6.ifr6_addr.sin6_port = 0;
    ifr6.ifr6_addr.sin6_addr = addr.getAddress();
    ifr6.ifr6_addr.sin6_scope_id = addr.getScopeId();
    ifr6.ifr6_prefixlen = prefixLength;
    ioctl(fd, Sockios.SIOCSIFADDR, ifr6);
  }
}
