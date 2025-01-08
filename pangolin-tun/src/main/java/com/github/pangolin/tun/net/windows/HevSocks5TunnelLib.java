package com.github.pangolin.tun.net.windows;

import com.sun.jna.Native;
import io.netty.util.internal.SystemPropertyUtil;

import static org.drasyl.channel.tun.jna.windows.loader.LibraryLoader.PREFER_SYSTEM;

/**
 * JNA based helper class to set the IP address and netmask for a given network device.
 */
public final class HevSocks5TunnelLib {
    private static final String DEFAULT_MODE = SystemPropertyUtil.get("tun.native.mode", PREFER_SYSTEM);

    static {
//            new LibraryLoader(HevSocks5TunnelLib.class).loadLibrary(DEFAULT_MODE, "libhev-socks5-tunnel");
        Native.register(HevSocks5TunnelLib.class, "libhev-socks5-tunnel.dylib");
    }

    private HevSocks5TunnelLib() {
        // JNA mapping
    }

    public static native int hev_socks5_tunnel_main_from_file (String config_path, int tun_fd);

    /**
     * hev_socks5_tunnel_quit:
     *
     * Stop the socks5 tunnel.
     *
     * Since: 2.4.6
     */
    public static native void hev_socks5_tunnel_quit ();

    public static void main(String[] args) {
        System.out.println();
    }
}