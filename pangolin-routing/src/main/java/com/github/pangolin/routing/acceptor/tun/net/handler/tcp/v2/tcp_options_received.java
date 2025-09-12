package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

// https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L111
public class tcp_options_received {
    public int ts_recent_stamp;
    public long ts_recent;
    public long rcv_tsval;
    public long rcv_tsecr;
    public long saw_tstmap;
    public boolean tstamp_ok;
    public int dsack;
    public boolean wscale_ok;
    public int sack_ok;
    public int smc_ok;
    public int snd_wscale;
    public int rcv_wscale;
    public int saw_unknown;
    public int unused;
    public int num_sacks;
    public int user_mss;
    public int mss_clamp;

}
