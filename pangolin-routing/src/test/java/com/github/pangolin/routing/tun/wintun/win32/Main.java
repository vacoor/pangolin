package com.github.pangolin.routing.tun.wintun.win32;

import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.DNS_SETTING_NAMESERVER;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.GAA_FLAG_INCLUDE_ALL_INTERFACES;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.GAA_FLAG_INCLUDE_GATEWAYS;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.GAA_FLAG_SKIP_ANYCAST;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.GAA_FLAG_SKIP_FRIENDLY_NAME;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.GAA_FLAG_SKIP_MULTICAST;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.GAA_FLAG_SKIP_UNICAST;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.DNS_INTERFACE_SETTINGS;
import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.*;
import static com.sun.jna.platform.win32.IPHlpAPI.*;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCloseAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCreateAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunEndSession;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunStartSession;

import com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib;
import com.google.common.base.Preconditions;
import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import freework.codec.Hex;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.jna.windows.Guid;
import org.drasyl.channel.tun.jna.windows.WinDef;
import org.drasyl.channel.tun.jna.windows.Wintun;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
public class Main {

    public static com.sun.jna.platform.win32.Guid.GUID luidToGuid(final long luid) {
        final LongByReference luidRef = new LongByReference(luid);
        final com.sun.jna.platform.win32.Guid.GUID.ByReference guidRef = new com.sun.jna.platform.win32.Guid.GUID.ByReference();
        IpHelpLib.INSTANCE.ConvertInterfaceLuidToGuid(luidRef, guidRef);
        return guidRef;
    }

    public static void setInterfaceDns(final com.sun.jna.platform.win32.Guid.GUID interfaceGuid,
                                       final int family, final InetAddress[] dnsServers, final String[] domains) {
        Preconditions.checkArgument(AF_INET == family || AF_INET6 == family, "ERROR_PROTOCOL_UNREACHABLE");

        final StringBuilder nameServers = new StringBuilder();
        for (final InetAddress dnsServer : dnsServers) {
            if ((AF_INET == family && dnsServer instanceof Inet4Address)
                    || (AF_INET6 == family && dnsServer instanceof Inet6Address)) {
                if (nameServers.length() > 0) {
                    nameServers.append(",");
                }
                nameServers.append(dnsServer.getHostAddress());
            }
        }
        final String searchList = String.join(",", domains);

        final DNS_INTERFACE_SETTINGS dnsInterfaceSettings = new DNS_INTERFACE_SETTINGS();
        dnsInterfaceSettings.Version = DNS_INTERFACE_SETTINGS_VERSION1;
        dnsInterfaceSettings.Flags = DNS_SETTING_NAMESERVER | DNS_SETTING_SEARCHLIST;
        dnsInterfaceSettings.NameServer = nameServers.toString();
        dnsInterfaceSettings.SearchList = searchList;

        if (AF_INET6 == family) {
            dnsInterfaceSettings.Flags |= DNS_SETTING_IPV6;
        }

        // For >= Windows 10 1809
        final int err = IpHelpLib.INSTANCE.SetInterfaceDnsSettings(interfaceGuid, dnsInterfaceSettings);
        if (WinError.ERROR_PROC_NOT_FOUND == err) {
            // For < Windows 10 1809
        }
        // TODO
    }

    private static void getInterfaceDns(final com.sun.jna.platform.win32.Guid.GUID interfaceGuid) {
        final DNS_INTERFACE_SETTINGS.ByReference dnsInterfaceSettings = new DNS_INTERFACE_SETTINGS.ByReference();
        dnsInterfaceSettings.Version = DNS_INTERFACE_SETTINGS_VERSION1;
        dnsInterfaceSettings.QueryAdapterName = DNS_SETTINGS_QUERY_ADAPTER_NAME;
        dnsInterfaceSettings.Flags = DNS_SETTING_NAMESERVER | DNS_SETTING_SEARCHLIST;

        IpHelpLib.INSTANCE.GetInterfaceDnsSettings(interfaceGuid, dnsInterfaceSettings);
        IpHelpLib.INSTANCE.FreeInterfaceDnsSettings(dnsInterfaceSettings.getPointer());
    }


    public static void main(String[] args) throws IOException {
//        System.out.println(NetioAPI.INSTANCE);
//        listAssociatedAddresses(AF_UNSPEC);
        /*
        getAdaptersAddresses();

        System.out.println("------------");


        System.exit(0);
        */
//        final String id = "{22430978-1194-4d70-b652-f1546d123aff}";
//        final String s = id.replaceAll("^\\{|\\}$|-", "");
//        final Guid.GUID guid = Guid.GUID.fromBinary(Hex.decode(s));
        final Guid.GUID guid = Guid.GUID.newGuid();

//        listAssociatedAddresses(IPHlpAPI.AF_UNSPEC);
        Wintun.WINTUN_ADAPTER_HANDLE adapter = null;
        Wintun.WINTUN_SESSION_HANDLE session = null;
        try {
            adapter = WintunCreateAdapter(new WString("iTun"), new WString("PAN"), guid);
            final Pointer p = new Memory(Native.POINTER_SIZE);
            WintunGetAdapterLUID(adapter, p);

            final long luid = p.getLong(0);

            setInterfaceDns(luidToGuid(luid), AF_INET, new InetAddress[]{InetAddress.getByName("192.168.1.1")}, new String[0]);

            session = WintunStartSession(adapter, new WinDef.DWORD(0x400000));

            System.out.println();
        } catch (final LastErrorException e) {
            if (session != null) {
                WintunEndSession(session);
            }

            if (adapter != null) {
                WintunCloseAdapter(adapter);
            }

            throw new IOException(e);
        }
    }


    /**
     * List all ip address related to this adapter.
     *
     * @param ipFamily Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @return List of [AdapterIPAddress], representing an IP.
     * */
    public static void listAssociatedAddresses(int ipFamily) throws UnknownHostException {
        final PointerByReference pointerByReference = new PointerByReference();
        final int err = IpHelpLib.INSTANCE.GetUnicastIpAddressTable(ipFamily, pointerByReference);
        // something wrong
        if (err != WinError.NO_ERROR && err != WinError.ERROR_NOT_FOUND)
            throw new RuntimeException("Failed to list unicast ip addresses:" + err);
        // no ip, return empty list
        if (err != WinError.NO_ERROR) {
            //return emptyList();
            System.out.println("EMPTY");
            return;
        }

        // parsing pointer
        final IpHelpLib.MIB_MULTICASTIPADDRESS_TABLE table = new IpHelpLib.MIB_MULTICASTIPADDRESS_TABLE(pointerByReference.getValue());
        // "MIB_UNICASTIPADDRESS_TABLE size not match. Expect ${table.NumEntries}, actual: ${table.Table.size}"
        assert table.NumEntries == table.Table.length;
        for (final IpHelpLib.MIB_UNICASTIPADDRESS_ROW row : table.Table) {
            if (AF_INET == row.Address.si_family) {
                final IpHelpLib.sockaddr_in v4 = (IpHelpLib.sockaddr_in) row.Address.getTypedValue(IpHelpLib.sockaddr_in.class);
                InetAddress inet4 = InetAddress.getByAddress(v4.sin_addr);
                System.out.println(inet4);
            } else if (AF_INET6 == row.Address.si_family) {
                final IpHelpLib.sockaddr_in6 v6 = (IpHelpLib.sockaddr_in6) row.Address.getTypedValue(IpHelpLib.sockaddr_in6.class);
                InetAddress inet6 = InetAddress.getByAddress(v6.sin6_addr);
                System.out.println(inet6);
            } else {
                //  Unknown si family: ${it.Address.si_family}
            }
            System.out.println(row.Address.si_family);
        }
        IpHelpLib.INSTANCE.FreeMibTable(table.getPointer());
    }


    private static void getAdaptersAddresses() {
        // The recommended method of calling the GetAdaptersAddresses function is to pre-allocate a
        // 15KB working buffer
        Memory buffer = new Memory(15 * 1024L);
        IntByReference size = new IntByReference(0);
        int flags = GAA_FLAG_SKIP_UNICAST | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST
                | GAA_FLAG_INCLUDE_GATEWAYS
                | GAA_FLAG_SKIP_FRIENDLY_NAME
                | GAA_FLAG_INCLUDE_ALL_INTERFACES;
        int error = IpHelpLib.INSTANCE.GetAdaptersAddresses(AF_UNSPEC, flags, Pointer.NULL, buffer, size);
        if (error == WinError.ERROR_BUFFER_OVERFLOW) {
            buffer = new Memory(size.getValue());
            error = IpHelpLib.INSTANCE.GetAdaptersAddresses(AF_UNSPEC, flags, Pointer.NULL, buffer, size);
            if (error != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(error);
            }
        }

        IpHelpLib.IP_ADAPTER_ADDRESSES_LH result = new IpHelpLib.IP_ADAPTER_ADDRESSES_LH(buffer);
        do {
            // only interfaces with IfOperStatusUp
            if (result.OperStatus == 1) {
                System.out.println(result.AdapterName + " -> " + result.NetworkGuid.toGuidString());
                IpHelpLib.IP_ADAPTER_DNS_SERVER_ADDRESS_XP dns = result.FirstDnsServerAddress;
//                IpHelpLib.IP_ADAPTER_GATEWAY_ADDRESS_LH dns = result.FirstGatewayAddress;
                while (dns != null) {
                    InetAddress address;
                    try {
                        address = dns.Address.toAddress();
                        System.out.println(address);

                        if (address instanceof Inet4Address || !address.isSiteLocalAddress()) {
//                            addNameserver(new InetSocketAddress(address, SimpleResolver.DEFAULT_PORT));
                        } else {
                            log.debug(
                                    "Skipped site-local IPv6 server address {} on adapter index {}",
                                    address,
                                    result.IfIndex);
                        }
                    } catch (UnknownHostException e) {
                        log.warn("Invalid nameserver address on adapter index {}", result.IfIndex, e);
                    }

                    dns = dns.Next;
                }

                log.warn(result.DnsSuffix.toString());
//                addSearchPath(result.DnsSuffix.toString());
                IpHelpLib.IP_ADAPTER_DNS_SUFFIX suffix = result.FirstDnsSuffix;
                while (suffix != null) {
//                    addSearchPath(String.valueOf(suffix._String));
                    System.out.println(String.valueOf(suffix._String));
                    suffix = suffix.Next;
                }
            }

            result = result.Next;
        } while (result != null);
    }
}
