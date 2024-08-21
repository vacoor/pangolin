package com.github.pangolin.routing.tun.wintun;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;

import java.io.IOException;

import static com.github.pangolin.routing.tun.wintun.WintunLib.*;
import static com.sun.jna.platform.win32.Guid.GUID;

public class WintunAdapter {
    private final WINTUN_ADAPTER_HANDLE adapter;

    public WintunAdapter(final WINTUN_ADAPTER_HANDLE adapter) {
        this.adapter = adapter;
    }

    public static WintunAdapter open(String name, final String type, final String guid) throws IOException {
        return open(name, type, GUID.fromString(guid));
    }

    /**
     *
     * @param name the name of the tun adapter
     * @param type the type of the tun adapter, null for open existing one, required when creating new adapter.
     * @param guid
     * @return
     * @throws IOException
     */
    public static WintunAdapter open(String name, final String type, final GUID guid) throws IOException {
        if (name == null) {
            name = "tun";
        }

        WINTUN_ADAPTER_HANDLE adapter = null;
        try {
            adapter = WintunOpenAdapter(new WString(name));
            if (null == adapter) {
                adapter = WintunCreateAdapter(new WString(name), new WString(type), guid);
            }
            return new WintunAdapter(adapter);
        } catch (final LastErrorException e) {
            if (adapter != null) {
                WintunCloseAdapter(adapter);
            }
            throw new IOException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        WintunAdapter.open("Wintun", "wintun", GUID.newGuid());
    }
}