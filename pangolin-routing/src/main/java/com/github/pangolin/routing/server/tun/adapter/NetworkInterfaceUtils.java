package com.github.pangolin.routing.server.tun.adapter;

import java.lang.reflect.Method;
import java.net.NetworkInterface;

public class NetworkInterfaceUtils {

    public static NetworkInterface getDefault() throws Exception {
        final Method getDefault = NetworkInterface.class.getDeclaredMethod("getDefault");
        getDefault.setAccessible(true);
        return (NetworkInterface) getDefault.invoke(null);
    }

}
