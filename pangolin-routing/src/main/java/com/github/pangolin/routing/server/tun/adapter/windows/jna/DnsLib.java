package com.github.pangolin.routing.server.tun.adapter.windows.jna;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;

/**
 * @see <a href="https://stackoverflow.com/questions/52007372/how-to-clear-flush-the-dns-cache-in-win32-apis">How to Clear/Flush the DNS Cache in Win32 API's</a>
 */
public final class DnsLib {

    static {
        Native.register("DNSAPI");
    }

    /**
     * DnsFlushResolverCache clears windows DNS Cache.
     * <p>
     * The effect is equivalent to the ipconfig/flushdns command.
     * The DnsFlushResolverCache function is exported in Dnsapi.dll
     * without any parameters.
     * <p>
     * He cannot be found in MSDN. It seems to be an unmarshented function.
     */
    public static native boolean DnsFlushResolverCache() throws LastErrorException;

}
