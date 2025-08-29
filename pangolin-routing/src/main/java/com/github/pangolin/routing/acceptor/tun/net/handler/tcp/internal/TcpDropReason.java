package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

public class TcpDropReason {

  /**
   * https://github.com/torvalds/linux/blob/master/include/net/dropreason-core.h#L127.
   */
  public static final int SKB_DROP_REASON_NOT_SPECIFIED = 0;
  public static final int SKB_DROP_REASON_TCP_FLAGS = 1;
  public static final int SKB_DROP_REASON_TCP_RESET = 2;
  public static final int SKB_DROP_REASON_TCP_CLOSE = 6;
  public static final int SKB_DROP_REASON_TCP_ZEROWINDOW = 3;
  public static final int SKB_DROP_REASON_TCP_OLD_DATA = 4;
  public static final int SKB_DROP_REASON_TCP_OVERWINDOW = 5;
  public static final int SKB_DROP_REASON_NO_SOCKET = 7;
  public static final int SKB_DROP_REASON_TCP_ABORT_ON_DATA = 8;
  public static final int SKB_DROP_REASON_TCP_ACK_UNSENT_DATA = 9;
  /**
   * TCP ACK is old, but in window.
   */
  public static final int SKB_DROP_REASON_TCP_OLD_ACK = 10;
  /**
   * TCP ACK is too old.
   */
  public static final int SKB_DROP_REASON_TCP_TOO_OLD_ACK = 11;

}
