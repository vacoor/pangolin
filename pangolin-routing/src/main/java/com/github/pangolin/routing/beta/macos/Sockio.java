package com.github.pangolin.routing.beta.macos;

import com.sun.jna.NativeLong;

/**
 * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/sockio.h">sockio.h</a>
 * @see <a href="https://github.com/dcuddeback/ioctl-rs/blob/master/src/os/macos.rs">macos ioctl</a>
 */
public interface Sockio {
  NativeLong SIOCGIFCONF = new NativeLong(0xc00c6924L);
  NativeLong SIOCGIFFLAGS = new NativeLong(0xc0206911L);
  NativeLong SIOCSIFFLAGS = new NativeLong(0x80206910L);
  NativeLong SIOCGIFADDR = new NativeLong(0xc0206921L);
  NativeLong SIOCSIFADDR = new NativeLong(0x8020690cL);
  NativeLong SIOCGIFDSTADDR = new NativeLong(0xc0206922L);
  NativeLong SIOCSIFDSTADDR = new NativeLong(0x8020690eL);
  NativeLong SIOCGIFBRDADDR = new NativeLong(0xc0206923L);
  NativeLong SIOCSIFBRDADDR = new NativeLong(0x80206913L);
  NativeLong SIOCGIFNETMASK = new NativeLong(0xc0206925L);
  NativeLong SIOCSIFNETMASK = new NativeLong(0x80206916L);
  NativeLong SIOCGIFMETRIC = new NativeLong(0xc0206917L);
  NativeLong SIOCSIFMETRIC = new NativeLong(0x80206918L);
  NativeLong SIOCGIFMTU = new NativeLong(0xc0206933L);
  NativeLong SIOCSIFMTU = new NativeLong(0x80206934L);
  NativeLong SIOCADDMULTI = new NativeLong(0x80206931L);
  NativeLong SIOCDELMULTI = new NativeLong(0x80206932L);


  NativeLong SIOCAIFADDR_IN6 = new NativeLong(0x8080691aL);

}
