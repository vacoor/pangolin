package com.github.pangolin.tun.net.linux.jna;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import com.sun.jna.platform.linux.LibC;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;

public interface LibC2 extends LibC {
  LibC2 INSTANCE = Native.load(NAME, LibC2.class);

  int getifaddrs(Structure ifap);

  void freeifaddrs(Structure ifa);

  int open(final String path, final int flags) throws LastErrorException;

  // int close(final int fd) throws LastErrorException;

  int read(final int fd, final byte[] buf, final long nBytes) throws LastErrorException;

  int read(final int fd, final ByteBuffer buf, final long nBytes) throws LastErrorException;

  int write(final int fd, final byte[] buf, final int nBytes) throws LastErrorException;


  int socket(final int domain,
                                  final int type,
                                  final int protocol) throws LastErrorException;

  int connect(final int socket,
                                   final Structure address,
                                   final int address_len) throws LastErrorException;
  @SuppressWarnings("java:S117")
  int getsockopt(final int socket,
                                      final int level,
                                      final int option_name,
                                      final Structure option_value,
                                      final IntByReference option_len) throws LastErrorException;

  int ioctl(final int fildes, final NativeLong request, final Structure argp) throws LastErrorException;

}
