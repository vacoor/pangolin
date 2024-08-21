package com.github.pangolin.routing.beta.tun.jna.win32;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.win32.W32APIOptions;

public interface NetioAPI extends Library {
    NetioAPI INSTANCE = Native.load("Netiohlp", NetioAPI.class, W32APIOptions.DEFAULT_OPTIONS);
}
