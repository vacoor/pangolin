package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.HZ;

public class SysctlOptions {

  public static final boolean sysctl_tcp_window_scaling = true;
  public static final int sysctl_tcp_pingpong_thresh = 1;

  public static final int sysctl_tcp_syn_retries = 5;
  public static final int sysctl_tcp_retries1 = 5;
  public static final int sysctl_tcp_retries2 = 5;
  /**
   * 有符号Window.
   */
  public static boolean ipv4_sysctl_tcp_workaround_signed_windows;
  public static boolean ipv4_sysctl_tcp_shrink_window;
  public static int ipv4_sysctl_tcp_rmem_2;
  public static int sysctl_rmem_max;
  public static int ipv4_sysctl_tcp_min_snd_mss;

  public static final int sysctl_tcp_keepalive_probes = 9;
  public static final int sysctl_tcp_keepalive_time = 7200 * HZ;
  public static final int sysctl_tcp_keepalive_intvl = 75 * HZ;

  public static final int sysctl_tcp_fin_timeout = 60 * HZ;

  public static final int sysctl_tcp_invalid_ratelimit = HZ / 2;

}
