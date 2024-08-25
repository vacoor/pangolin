package com.github.pangolin.routing.tun.wintun.win32.jna;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.win32.W32APIOptions;

/**
 * @see <a href="https://stackoverflow.com/questions/52007372/how-to-clear-flush-the-dns-cache-in-win32-apis">How to Clear/Flush the DNS Cache in Win32 API's</a>
 */
public interface DnsLib extends Library {

    DnsLib INSTANCE = Native.load("DNSAPI", DnsLib.class, W32APIOptions.DEFAULT_OPTIONS);

    /**
     * DnsFlushResolverCache clears windows DNS Cache.
     * <p>
     * The effect is equivalent to the ipconfig/flushdns command.
     * The DnsFlushResolverCache function is exported in Dnsapi.dll
     * without any parameters.
     * <p>
     * He cannot be found in MSDN. It seems to be an unmarshented function.
     */
    boolean DnsFlushResolverCache() throws LastErrorException;

}
