package com.github.pangolin.tun.net.linux.jna;

import com.sun.jna.NativeLong;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/sockios.h">sockios.h</a>
 */
public interface Sockios {

  /**
   * get flags.
   */
  NativeLong SIOCGIFFLAGS = new NativeLong(0x8913);

  /**
   * set flags.
   */
  NativeLong SIOCSIFFLAGS = new NativeLong(0x8914);

  /**
   * get PA address.
   */
  NativeLong SIOCGIFADDR = new NativeLong(0x8915);
  /**
   * set PA address.
   */
  NativeLong SIOCSIFADDR = new NativeLong(0x8916);
  /**
   * get network PA mask.
   */
  NativeLong SIOCGIFNETMASK = new NativeLong(0x891b);
  /**
   * set network PA mask.
   */
  NativeLong SIOCSIFNETMASK = new NativeLong(0x891c);

  /* get metric			*/
  NativeLong SIOCGIFMETRIC = new NativeLong(0x891d);

  /* set metric			*/
  NativeLong SIOCSIFMETRIC = new NativeLong(0x891e);

  /* get MTU size			*/
  NativeLong SIOCGIFMTU = new NativeLong(0x8921);

  /* set MTU size			*/
  NativeLong SIOCSIFMTU = new NativeLong(0x8922);

  NativeLong SIOCGIFINDEX = new NativeLong(0x8933);

  NativeLong SIOGIFINDEX = SIOCGIFINDEX;

  NativeLong SIOCDIFADDR = new NativeLong(0x8936);
}
