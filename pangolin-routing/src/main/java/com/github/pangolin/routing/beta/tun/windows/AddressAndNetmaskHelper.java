package com.github.pangolin.routing.beta.tun.windows;

import com.sun.jna.LastErrorException;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.LongByReference;
import io.netty.util.internal.SystemPropertyUtil;
import org.drasyl.channel.tun.jna.windows.loader.LibraryLoader;

import java.io.IOException;

import static org.drasyl.channel.tun.jna.windows.loader.LibraryLoader.PREFER_SYSTEM;

/**
 * JNA based helper class to set the IP address and netmask for a given network device.
 */
public final class AddressAndNetmaskHelper {
    private static final String DEFAULT_MODE = SystemPropertyUtil.get("tun.native.mode", PREFER_SYSTEM);

    static {
        try {
            new LibraryLoader(AddressAndNetmaskHelper.class).loadLibrary(DEFAULT_MODE, "libdtun");
        }
        catch (final IOException e) {
            throw new RuntimeException(e); // NOSONAR
        }
    }

    private AddressAndNetmaskHelper() {
        // JNA mapping
    }

    public static native WinDef.DWORD setIPv4AndNetmask(final LongByReference luid,
                                                        final String ip,
                                                        final int mask) throws LastErrorException;

    public static native WinDef.DWORD setIPv6AndNetmask(final LongByReference luid,
                                                        final String ip,
                                                        final int mask) throws LastErrorException;
}