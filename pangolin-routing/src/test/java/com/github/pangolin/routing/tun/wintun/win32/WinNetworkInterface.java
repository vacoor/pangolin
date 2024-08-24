package com.github.pangolin.routing.tun.wintun.win32;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

import static com.github.pangolin.routing.tun.wintun.win32.IpHelpLib.*;
import static com.sun.jna.platform.win32.Guid.GUID;

/**
 * @see <a href="https://github.com/WireGuard/wireguard-windows/blob/master/tunnel/winipcfg/luid.go">luid</a>
 */
@Slf4j
public class WinNetworkInterface {

    public static long interfaceNameToLuid(final String interfaceName) {
        final LongByReference luidRef = new LongByReference();
        INSTANCE.ConvertInterfaceNameToLuidW(interfaceName, luidRef);
        return luidRef.getValue();
    }

    public static long interfaceAliasToLuid(final String interfaceAlias) {
        final LongByReference luidRef = new LongByReference();
        INSTANCE.ConvertInterfaceAliasToLuid(interfaceAlias, luidRef);
        return luidRef.getValue();
    }

    public static String interfaceLuidToName(final long interfaceLuid) {
        final LongByReference luidRef = new LongByReference(interfaceLuid);
        final char[] buff = new char[NDIS_IF_MAX_STRING_SIZE + 1];

        INSTANCE.ConvertInterfaceLuidToNameW(luidRef, buff, buff.length);
        return stringify(buff);
    }

    public static String interfaceLuidToAlias(final long interfaceLuid) {
        final LongByReference luidRef = new LongByReference(interfaceLuid);
        final char[] buff = new char[NDIS_IF_MAX_STRING_SIZE + 1];

        INSTANCE.ConvertInterfaceLuidToAlias(luidRef, buff, buff.length);
        return stringify(buff);
    }

    public static GUID interfaceLuidToGuid(final long interfaceLuid) {
        final LongByReference luidRef = new LongByReference(interfaceLuid);
        final GUID.ByReference guidRef = new GUID.ByReference();
        INSTANCE.ConvertInterfaceLuidToGuid(luidRef, guidRef);
        return guidRef;
    }

    private static String stringify(final char[] buff) {
        for (int i = 0; i < buff.length; i++) {
            if ('\0' == buff[i]) {
                return String.valueOf(buff, 0, i);
            }
        }
        return String.valueOf(buff);
    }

    // ------------------------ START Interface related ------------------------


    public static int getMTU(final long interfaceLuid, final int family) {
        return getInterfaceRow(interfaceLuid, family).NlMtu;
    }

    public static int getMetric(final long interfaceLuid, final int family) {
        return getInterfaceRow(interfaceLuid, family).Metric;
    }

    private static MIB_IPINTERFACE_ROW getInterfaceRow(final long interfaceLuid, final int family) {
        final MIB_IPINTERFACE_ROW row = new MIB_IPINTERFACE_ROW();
        row.InterfaceLuid = interfaceLuid;
        row.Family = family;

        final int err = INSTANCE.GetIpInterfaceEntry(row);
        return row;
    }

    public static void setMTU(final long interfaceLuid, final int family, final int mtu) {
        final MIB_IPINTERFACE_ROW row = new MIB_IPINTERFACE_ROW();
        INSTANCE.InitializeIpInterfaceEntry(row);
        row.Family = family;
        row.InterfaceLuid = interfaceLuid;

        final int err = INSTANCE.GetIpInterfaceEntry(row);

        row.NlMtu = mtu;
        INSTANCE.SetIpInterfaceEntry(row);
    }


    public void setIpInterface(final long interfaceLuid) {
        final MIB_IPINTERFACE_ROW row = new MIB_IPINTERFACE_ROW();
        INSTANCE.InitializeIpInterfaceEntry(row);
        row.InterfaceLuid = interfaceLuid;

        INSTANCE.SetIpInterfaceEntry(row);
        System.out.println();
    }


    // ------------------------ END Interface related ------------------------


    // ------------------------ START UnicastIP related ------------------------

    public static void addInterfaceAddress(final long interfaceLuid, final InetAddress address, final byte prefixLength) {
        final MIB_UNICASTIPADDRESS_ROW row = createMibUnicastIpAddressRow(interfaceLuid, address);
        row.OnLinkPrefixLength = prefixLength;
        row.ValidLifetime = 0xffffffff;
        row.PreferredLifetime = 0xffffffff;
        row.DadState = 4;

        int err = INSTANCE.CreateUnicastIpAddressEntry(row);
        if (WinError.NO_ERROR != err && err != WinError.ERROR_OBJECT_ALREADY_EXISTS) {
            throw new RuntimeException("Failed to create new MIB_UNICASTIPADDRESS_ROW: " + err);
        }
    }

    public static void deleteInterfaceAddress(final long interfaceLuid, final InetAddress address, final byte prefixLength) {
        final MIB_UNICASTIPADDRESS_ROW row = createMibUnicastIpAddressRow(interfaceLuid, address);
        row.OnLinkPrefixLength = prefixLength;

        int err = INSTANCE.DeleteUnicastIpAddressEntry(row);
        if (WinError.NO_ERROR != err) {
            throw new Win32Exception(err);
        }
    }

    public static void setInterfaceAddress(final long interfaceLuid, final InetAddress address, final byte prefixLength) {
        final int family = address instanceof Inet4Address ? AF_INET : (address instanceof Inet6Address ? AF_INET6 : AF_UNSPEC);
        flushInterfaceAddresses(interfaceLuid, family);
        addInterfaceAddress(interfaceLuid, address, prefixLength);
    }

    /**
     * @param family Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     */
    public static void flushInterfaceAddresses(final long interfaceLuid, int family) {
        final MIB_UNICASTIPADDRESS_TABLE table = GetUnicastIpAddressTable(family);
        for (final MIB_UNICASTIPADDRESS_ROW row : table.Table) {
            /*
            if (AF_INET == row.Address.si_family) {
                final sockaddr_in v4 = (sockaddr_in) row.Address.getTypedValue(sockaddr_in.class);
                InetAddress inet4 = InetAddress.getByAddress(v4.sin_addr);
                System.out.println(inet4);
            } else if (AF_INET6 == row.Address.si_family) {
                final sockaddr_in6 v6 = (sockaddr_in6) row.Address.getTypedValue(sockaddr_in6.class);
                InetAddress inet6 = InetAddress.getByAddress(v6.sin6_addr);
                System.out.println(inet6);
            } else {
                //  Unknown si family: ${it.Address.si_family}
            }
            System.out.println(row.Address.si_family);
            */
            if (row.InterfaceLuid == interfaceLuid) {
                INSTANCE.DeleteUnicastIpAddressEntry(row);
            }
        }
        INSTANCE.FreeMibTable(table.getPointer());
    }


    /**
     * Create and initialize a [MIB_UNICASTIPADDRESS_ROW], fill the luid and ip.
     */
    private static MIB_UNICASTIPADDRESS_ROW createMibUnicastIpAddressRow(final long interfaceLuid, InetAddress address) {
        final MIB_UNICASTIPADDRESS_ROW row = new MIB_UNICASTIPADDRESS_ROW();
        INSTANCE.InitializeUnicastIpAddressEntry(row);

        row.InterfaceLuid = interfaceLuid;

        if (address instanceof Inet4Address) {
            final sockaddr_in sockaddrIn = new sockaddr_in();
            sockaddrIn.sin_family = AF_INET;
            sockaddrIn.sin_port = 0;
            sockaddrIn.sin_addr = address.getAddress();
            row.Address.setTypedValue(sockaddrIn);
        } else if (address instanceof Inet6Address) {
            final sockaddr_in6 sockaddrIn6 = new sockaddr_in6();
            sockaddrIn6.sin6_family = AF_INET6;
            sockaddrIn6.sin6_port = 0;
            sockaddrIn6.sin6_addr = address.getAddress();
            // sockaddrIn6.sin6_scope_id = ((Inet6Address) address).getScopeId();
            row.Address.setTypedValue(sockaddrIn6);
        }
        return row;
    }

    private static MIB_UNICASTIPADDRESS_TABLE GetUnicastIpAddressTable(final int family) {
        final PointerByReference pointerByRef = new PointerByReference();
        final int err = INSTANCE.GetUnicastIpAddressTable(family, pointerByRef);
        // something wrong
        if (err != WinError.NO_ERROR && err != WinError.ERROR_NOT_FOUND)
            throw new RuntimeException("Failed to list unicast ip addresses:" + err);
        // no ip, return empty list
        if (err != WinError.NO_ERROR) {
            //return emptyList();
            System.out.println("EMPTY");
            return null;
        }

        // parsing pointer
        final MIB_UNICASTIPADDRESS_TABLE table = new MIB_UNICASTIPADDRESS_TABLE(pointerByRef.getValue());
        // "MIB_UNICASTIPADDRESS_TABLE size not match. Expect ${table.NumEntries}, actual: ${table.Table.size}"
        assert table.NumEntries == table.Table.length;
        return table;
    }

    private static MIB_UNICASTIPADDRESS_ROW GetUnicastIpAddress(final long interfaceLuid,
                                                                final InetAddress address) {
        final MIB_UNICASTIPADDRESS_ROW row = new MIB_UNICASTIPADDRESS_ROW();
        row.InterfaceLuid = interfaceLuid;
        /*
        row.Address.Ipv4.sin_family = AF_INET;
        row.Address.Ipv4.sin_port = 0;
        row.Address.Ipv4.sin_addr = address;
        */
        if (address instanceof Inet4Address) {
            final sockaddr_in sockaddrIn = new sockaddr_in();
            sockaddrIn.sin_family = AF_INET;
            sockaddrIn.sin_port = 0;
            sockaddrIn.sin_addr = address.getAddress();

            row.Address.si_family = AF_INET;
            row.Address.setTypedValue(sockaddrIn);
        } else if (address instanceof Inet6Address) {
            final sockaddr_in6 sockaddrIn6 = new sockaddr_in6();
            sockaddrIn6.sin6_family = AF_INET6;
            sockaddrIn6.sin6_port = 0;
            sockaddrIn6.sin6_addr = address.getAddress();
            // sockaddrIn6.sin6_scope_id = ((Inet6Address) address).getScopeId();

            row.Address.si_family = AF_INET6;
            row.Address.setTypedValue(sockaddrIn6);
        }

        int i = INSTANCE.GetUnicastIpAddressEntry(row);
        return row;
    }

    // ------------------------ END UnicastIP related ------------------------


    // ------------------------ START DNS related ------------------------


    private static void getInterfaceDns(GUID interfaceGuid) {
        final DNS_INTERFACE_SETTINGS.ByReference dnsInterfaceSettings = new DNS_INTERFACE_SETTINGS.ByReference();
        dnsInterfaceSettings.Version = DNS_INTERFACE_SETTINGS_VERSION1;
        dnsInterfaceSettings.QueryAdapterName = DNS_SETTINGS_QUERY_ADAPTER_NAME;
        dnsInterfaceSettings.Flags = DNS_SETTING_NAMESERVER | DNS_SETTING_SEARCHLIST;

        INSTANCE.GetInterfaceDnsSettings(interfaceGuid, dnsInterfaceSettings);
        INSTANCE.FreeInterfaceDnsSettings(dnsInterfaceSettings.getPointer());
    }

    public static void setInterfaceDns(final GUID interfaceGuid, final int family,
                                       final InetAddress[] dnsServers, final String[] domains) {
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
        final int err = INSTANCE.SetInterfaceDnsSettings(interfaceGuid, dnsInterfaceSettings);
        if (WinError.ERROR_PROC_NOT_FOUND == err) {
            // For < Windows 10 1809
        }
        // TODO
    }

    // ------------------------ END DNS related ------------------------

    // ------------------------ START AdapterAddresses related ------------------------

    public static long friendlyNameToLuid(final String friendlyName) {
        final int flags = GAA_FLAG_SKIP_UNICAST | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST
                | GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_FRIENDLY_NAME | GAA_FLAG_INCLUDE_ALL_INTERFACES;
        IP_ADAPTER_ADDRESSES_LH addresses = GetAdaptersAddresses(AF_UNSPEC, flags);
        while (null != addresses) {
            if (Objects.equals(friendlyName, addresses.FriendlyName.toString())) {
                return addresses.Luid;
            }
            addresses = addresses.Next;
        }
        return 0;
    }

    public static List<InetAddress> getInterfaceAllDns(final long interfaceLuid) {
        final int flags = GAA_FLAG_SKIP_UNICAST | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST
                | GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_FRIENDLY_NAME | GAA_FLAG_INCLUDE_ALL_INTERFACES;

        IP_ADAPTER_ADDRESSES_LH addresses = GetAdaptersAddresses(AF_UNSPEC, flags);
        do {
            // only interfaces with IfOperStatusUp
            // if (addresses.OperStatus == 1) {
            if (addresses.Luid == interfaceLuid) {
                final List<InetAddress> nameServers = Lists.newLinkedList();
                for (IP_ADAPTER_DNS_SERVER_ADDRESS_XP dns = addresses.FirstDnsServerAddress; null != dns; dns = dns.Next) {
                    // IP_ADAPTER_GATEWAY_ADDRESS_LH gateway = addresses.FirstGatewayAddress;
                    try {
                        final InetAddress address = dns.Address.toAddress();
                        if (address instanceof Inet4Address || !address.isSiteLocalAddress()) {
                            nameServers.add(address);
//                            addNameserver(new InetSocketAddress(address, SimpleResolver.DEFAULT_PORT));
                        } else {
                            log.debug(
                                    "Skipped site-local IPv6 server address {} on adapter index {}",
                                    address,
                                    addresses.IfIndex);
                        }
                    } catch (UnknownHostException e) {
                        log.warn("Invalid nameserver address on adapter index {}", addresses.IfIndex, e);
                    }
                    dns = dns.Next;
                }

                log.debug("DnsSuffix: {}", addresses.DnsSuffix);
//                addSearchPath(result.DnsSuffix.toString());
                for (IP_ADAPTER_DNS_SUFFIX suffix = addresses.FirstDnsSuffix; null != suffix; suffix = suffix.Next) {
                    log.debug("SearchPath: {}", suffix._String);
                }
                return nameServers;
            }
            addresses = addresses.Next;
        } while (addresses != null);
        return null;
    }

    private static IP_ADAPTER_ADDRESSES_LH GetAdaptersAddresses(final int family, final int gaaFlags) {
        // The recommended method of calling the GetAdaptersAddresses function is to pre-allocate a
        // 15KB working buffer
        Memory buffer = new Memory(15 * 1024L);
        final IntByReference size = new IntByReference(0);
        int error = INSTANCE.GetAdaptersAddresses(family, gaaFlags, Pointer.NULL, buffer, size);
        if (error == WinError.ERROR_BUFFER_OVERFLOW) {
            buffer = new Memory(size.getValue());
            error = INSTANCE.GetAdaptersAddresses(family, gaaFlags, Pointer.NULL, buffer, size);
            if (error != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(error);
            }
        }
        return new IP_ADAPTER_ADDRESSES_LH(buffer);
    }

    // ------------------------ END AdapterAddresses related ------------------------

}