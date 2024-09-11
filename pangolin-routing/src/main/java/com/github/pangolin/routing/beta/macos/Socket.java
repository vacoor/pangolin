package com.github.pangolin.routing.beta.macos;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/socket.h">socket.h</a>
 */
public interface Socket {


  /**
   * stream socket.
   */
  int SOCK_STREAM = 1;

  /**
   * datagram socket.
   */
  int SOCK_DGRAM = 2;

  /**
   * .
   */
  int AF_UNSPEC = 0;


  /**
   * IPv4.
   */
  int AF_INET = 2;

  /**
   * IPv6.
   */
  int AF_INET6 = 30;

  int AF_SYSTEM = 32;

}
