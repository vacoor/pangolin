package com.github.pangolin.tun.net.linux;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.linux.LibC;

public interface LibC2 extends LibC {
  LibC2 INSTANCE = Native.load(NAME, LibC2.class);

  int getifaddrs(Structure ifap);

  void freeifaddrs(Structure ifa);

}
