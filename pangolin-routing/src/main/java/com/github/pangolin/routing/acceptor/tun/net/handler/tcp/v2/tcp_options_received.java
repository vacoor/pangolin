package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

// https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L111
public class tcp_options_received {
    public long saw_tstmap;
    public boolean tstamp_ok;
    public boolean wsacle_ok;
    public int snd_wscale;
    public int rcv_wscale;
    public int user_mss;
    public int mss_clamp;
    public boolean wscale_ok;

}
