package com.github.pangolin.tun.net.windows;

import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.NetworkInterfaceEx;
import com.github.pangolin.tun.net.windows.jna.DnsLib;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.*;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;

import static com.github.pangolin.tun.net.windows.jna.DnsLib.DnsFlushResolverCache;
import static com.github.pangolin.tun.net.windows.jna.IpHelpLib.*;
import static com.sun.jna.platform.win32.Guid.GUID;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_INET;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_INET6;

/**
 * @see <a href="https://github.com/WireGuard/wireguard-windows/blob/master/tunnel/winipcfg/luid.go">luid</a>
 */
@Slf4j
public class WindowsNetworkInterfaceEx implements NetworkInterfaceEx {
    private final long interfaceLuid;

    public WindowsNetworkInterfaceEx(final long interfaceLuid) {
        this.interfaceLuid = interfaceLuid;
    }

    public long luid() {
        return interfaceLuid;
    }

    public int index() {
        return interfaceLuidToIndex(interfaceLuid);
    }

    public GUID guid() {
        return interfaceLuidToGuid(interfaceLuid);
    }

    public String name() {
        return interfaceLuidToName(interfaceLuid);
    }

    public String alias() {
        return interfaceLuidToAlias(interfaceLuid);
    }

    @Override
    public List<InterfaceAddressEx> getInterfaceAddresses() {
        /* java.net.NetworkInterfaceAddresses#getNetworkPrefixLength is wrong.
        final List<InterfaceAddress> addrs = networkInterface().getInterfaceAddresses();
        final List<InterfaceAddressEx> addr2s = new LinkedList<>();
        for (final InterfaceAddress addr : addrs) {
            addr2s.add(InterfaceAddressEx.of(addr.getAddress(), addr.getNetworkPrefixLength()));
        }
        return addr2s;
        */
        return getInterfaceAddresses(interfaceLuid, AF_UNSPEC);
    }

    @Override
    public void setInterfaceAddress(final InterfaceAddressEx address) {
        setInterfaceAddress(interfaceLuid, address.getAddress(), (byte) address.getNetworkPrefixLength());
    }

    @Override
    public void addInterfaceAddress(final InterfaceAddressEx address) {
        addInterfaceAddress(interfaceLuid, address.getAddress(), (byte) address.getNetworkPrefixLength());
    }

    @Override
    public void deleteInterfaceAddress(final InterfaceAddressEx address) {
        deleteInterfaceAddress(interfaceLuid, address.getAddress(), (byte) address.getNetworkPrefixLength());
    }

    @Override
    public void flushInterfaceAddresses() {
        flushInterfaceAddresses(interfaceLuid, AF_UNSPEC);
    }

    @Override
    public int getMTU() throws SocketException {
        return networkInterface().getMTU();
    }

    private NetworkInterface networkInterface() {
        try {
            /*-
             * java.net.NetworkInterface is SNAPSHOT and name/displayName, getInterfaceAddresses().networkPrefixLength is wrong.
             */
            return NetworkInterface.getByIndex(index());
        } catch (final SocketException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<InetAddress> getInterfaceDns(final boolean manualSetOnly) {
        return getInterfaceDns(interfaceLuid, manualSetOnly);
    }

    public void setInterfaceDns(final InetAddress[] nameServers) {
        final Inet4Address[] v4 = Arrays.stream(nameServers).filter(a -> a instanceof Inet4Address).toArray(Inet4Address[]::new);
        final Inet4Address[] v6 = Arrays.stream(nameServers).filter(a -> a instanceof Inet6Address).toArray(Inet4Address[]::new);
        final GUID interfaceGuid = interfaceLuidToGuid(interfaceLuid);
        setInterfaceDns(interfaceGuid, AF_INET, v4, new String[0]);
        setInterfaceDns(interfaceGuid, AF_INET6, v6, new String[0]);
    }

    public void flushInterfaceDns() {
        flushInterfaceDns(interfaceLuidToGuid(interfaceLuid), AF_UNSPEC);
    }

    // ------------------------ START Static method ------------------------

    public static WindowsNetworkInterfaceEx getByIndex(final int index) throws SocketException {
        return of(NetworkInterface.getByIndex(index));
    }

    public static WindowsNetworkInterfaceEx getByInetAddress(final InetAddress addr) throws SocketException {
        return of(NetworkInterface.getByInetAddress(addr));
    }

    public static WindowsNetworkInterfaceEx getByAlias(final String interfaceAlias) throws SocketException {
        /*-
         * java.net.NetworkInterface
         * - name: eth0 (windows平台也是)
         * - displayName: 以太网 2
         * 对应
         * IpHlpAPI GetAdapterAddress
         * - adapterName: {6CE10339-9804-4AD3-8310-4F81CE0BE645}
         * - FriendlyName: 以太网 2
         * - Description: Realtek PCIe GbE Family Controller #2
         * 两者有些情况下 displayName 与 FriendlyName 一致, 某些情况下完全不一样.
         */
        return getByLuid(interfaceAliasToLuid(interfaceAlias));
    }

    public static WindowsNetworkInterfaceEx getByLuid(final long interfaceLuid) throws SocketException {
        return new WindowsNetworkInterfaceEx(interfaceLuid);
    }

    public static WindowsNetworkInterfaceEx of(final NetworkInterface ni) {
        final long interfaceLuid = interfaceIndexToLuid(ni.getIndex());
        return new WindowsNetworkInterfaceEx(interfaceLuid);
    }

    public static List<InetAddress> allDns() throws SocketException {
        final List<InetAddress> nameServers = Lists.newLinkedList();
        final Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        while (nis.hasMoreElements()) {
            final NetworkInterface ni = nis.nextElement();
            if (ni.isLoopback() || !ni.isUp()) {
                continue;
            }

            final int index = ni.getIndex();
            final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByIndex(index);
            final List<InetAddress> interfaceDns = nix.getInterfaceDns(false);

            /*
            final String name = String.format(
                    "[Java NetworkInterface name = %s, display name = %s, System Adapter name = %s, alias = %s]",
                    ni.getName(), ni.getDisplayName(), nix.name(), nix.alias()
            );
            System.out.println(String.format("%s %s", name, interfaceDns));
            */
            nameServers.addAll(interfaceDns);
        }
        return nameServers;
    }

    public static void flushDnsCache() {
        DnsFlushResolverCache();
    }


    // ------------------------ END Static method ------------------------


    // ------------------------ START JNA utility method ------------------------


    private static long interfaceNameToLuid(final String interfaceName) {
        final LongByReference luidRef = new LongByReference();
        final int err = INSTANCE.ConvertInterfaceNameToLuidW(interfaceName, luidRef);
        assertNoError(err, "ConvertInterfaceNameToLuidW failed: %s", interfaceName);
        return luidRef.getValue();
    }

    private static long interfaceAliasToLuid(final String interfaceAlias) {
        final LongByReference luidRef = new LongByReference();
        final int err = INSTANCE.ConvertInterfaceAliasToLuid(interfaceAlias, luidRef);
        assertNoError(err, "ConvertInterfaceAliasToLuid failed: %s", interfaceAlias);
        return luidRef.getValue();
    }

    private static long interfaceIndexToLuid(final int interfaceIndex) {
        final LongByReference luidRef = new LongByReference();
        final int err = INSTANCE.ConvertInterfaceIndexToLuid(interfaceIndex, luidRef);
        assertNoError(err, "ConvertInterfaceIndexToLuid failed: %s", interfaceIndex);
        return luidRef.getValue();
    }

    private static long interfaceGuidToLuid(final GUID interfaceGuid) {
        final LongByReference luidRef = new LongByReference();
        final int err = INSTANCE.ConvertInterfaceGuidToLuid(interfaceGuid, luidRef);
        assertNoError(err, "ConvertInterfaceGuidToLuid failed: %s", interfaceGuid);
        return luidRef.getValue();
    }

    private static String interfaceLuidToName(final long interfaceLuid) {
        final LongByReference luidRef = new LongByReference(interfaceLuid);
        final char[] buff = new char[NDIS_IF_MAX_STRING_SIZE + 1];
        final int err = INSTANCE.ConvertInterfaceLuidToNameW(luidRef, buff, buff.length);
        assertNoError(err, "ConvertInterfaceLuidToNameW failed: %s", interfaceLuid);
        return stringify(buff);
    }

    private static String interfaceLuidToAlias(final long interfaceLuid) {
        final LongByReference luidRef = new LongByReference(interfaceLuid);
        final char[] buff = new char[NDIS_IF_MAX_STRING_SIZE + 1];
        final int err = INSTANCE.ConvertInterfaceLuidToAlias(luidRef, buff, buff.length);
        assertNoError(err, "ConvertInterfaceLuidToAlias failed: %s", interfaceLuid);
        return stringify(buff);
    }

    static GUID interfaceLuidToGuid(final long interfaceLuid) {
        final LongByReference luidRef = new LongByReference(interfaceLuid);
        final GUID.ByReference guidRef = new GUID.ByReference();
        final int err = INSTANCE.ConvertInterfaceLuidToGuid(luidRef, guidRef);
        assertNoError(err, "ConvertInterfaceLuidToGuid failed: %s", interfaceLuid);
        return guidRef;
    }

    private static int interfaceLuidToIndex(final long interfaceLuid) {
        final IntByReference indexRef = new IntByReference();
        final LongByReference luidRef = new LongByReference(interfaceLuid);
        final int err = INSTANCE.ConvertInterfaceLuidToIndex(luidRef, indexRef);
        assertNoError(err, "ConvertInterfaceLuidToIndex failed: %s", interfaceLuid);
        return indexRef.getValue();
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


    static int getMTU(final long interfaceLuid, final int family) {
        return getInterfaceRow(interfaceLuid, family).NlMtu;
    }

    private static int getMetric(final long interfaceLuid, final int family) {
        return getInterfaceRow(interfaceLuid, family).Metric;
    }

    private static MIB_IPINTERFACE_ROW getInterfaceRow(final long interfaceLuid, final int family) {
        final MIB_IPINTERFACE_ROW row = new MIB_IPINTERFACE_ROW();
        row.InterfaceLuid = interfaceLuid;
        row.Family = family;

        final int err = INSTANCE.GetIpInterfaceEntry(row);
        assertNoError(err, "GetIpInterfaceEntry failed: luid = %s, family=%s", interfaceLuid, family);
        return row;
    }

    static void setMTU(final long interfaceLuid, final int family, final int mtu) {
        final MIB_IPINTERFACE_ROW row = new MIB_IPINTERFACE_ROW();
        INSTANCE.InitializeIpInterfaceEntry(row);
        row.Family = family;
        row.InterfaceLuid = interfaceLuid;

        final int err = INSTANCE.GetIpInterfaceEntry(row);
        assertNoError(err, "GetIpInterfaceEntry failed: luid = %s, family=%s", interfaceLuid, family, mtu);

        row.NlMtu = mtu;
        final int err2 = INSTANCE.SetIpInterfaceEntry(row);
        assertNoError(err2, "SetIpInterfaceEntry failed: luid = %s, family=%s, MTU=%s", interfaceLuid, family, mtu);
    }

    // ------------------------ END Interface related ------------------------


    // ------------------------ START UnicastIP related ------------------------


    private static List<InterfaceAddressEx> getInterfaceAddresses(final long interfaceLuid, final int family) {
        final List<InterfaceAddressEx> addresses = new LinkedList<>();
        final MIB_UNICASTIPADDRESS_TABLE table = GetUnicastIpAddressTable(family);
        try {
            for (final MIB_UNICASTIPADDRESS_ROW row : table.Table) {
                if (interfaceLuid != row.InterfaceLuid) {
                    continue;
                }
                final byte prefixLength = row.OnLinkPrefixLength;
                if (AF_INET == row.Address.si_family) {
                    final sockaddr_in v4 = (sockaddr_in) row.Address.getTypedValue(sockaddr_in.class);
                    addresses.add(InterfaceAddressEx.of(toInetAddress(v4.sin_addr), prefixLength));
                } else if (AF_INET6 == row.Address.si_family) {
                    final sockaddr_in6 v6 = (sockaddr_in6) row.Address.getTypedValue(sockaddr_in6.class);
                    addresses.add(InterfaceAddressEx.of(toInetAddress(v6.sin6_addr), prefixLength));
                } else {
                    throw new IllegalStateException("Unknown si family: " + row.Address.si_family);
                }
            }
            return addresses;
        } finally {
            INSTANCE.FreeMibTable(table.getPointer());
        }
    }

    private static InetAddress toInetAddress(final byte[] sinAddr) {
        try {
            return InetAddress.getByAddress(sinAddr);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void addInterfaceAddress(final long interfaceLuid, final InetAddress address, final byte prefixLength) {
        final MIB_UNICASTIPADDRESS_ROW row = createMibUnicastIpAddressRow(interfaceLuid, address);
        row.OnLinkPrefixLength = prefixLength;
        row.ValidLifetime = 0xffffffff;
        row.PreferredLifetime = 0xffffffff;
        row.DadState = 4;

        final int err = INSTANCE.CreateUnicastIpAddressEntry(row);
        if (WinError.NO_ERROR != err && err != WinError.ERROR_OBJECT_ALREADY_EXISTS) {
            throw new IllegalStateException("CreateUnicastIpAddressEntry failed: " + err);
        }
    }

    private static void deleteInterfaceAddress(final long interfaceLuid, final InetAddress address, final byte prefixLength) {
        final MIB_UNICASTIPADDRESS_ROW row = createMibUnicastIpAddressRow(interfaceLuid, address);
        row.OnLinkPrefixLength = prefixLength;

        final int err = INSTANCE.DeleteUnicastIpAddressEntry(row);
        assertNoError(err, "DeleteUnicastIpAddressEntry failed: luid = %s", interfaceLuid);
    }

    private static void setInterfaceAddress(final long interfaceLuid, final InetAddress address, final byte prefixLength) {
        final int family = address instanceof Inet4Address ? AF_INET : (address instanceof Inet6Address ? AF_INET6 : AF_UNSPEC);
        flushInterfaceAddresses(interfaceLuid, family);
        addInterfaceAddress(interfaceLuid, address, prefixLength);
    }

    /**
     * @param family Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     */
    private static void flushInterfaceAddresses(final long interfaceLuid, int family) {
        final MIB_UNICASTIPADDRESS_TABLE table = GetUnicastIpAddressTable(family);
        try {
            for (final MIB_UNICASTIPADDRESS_ROW row : table.Table) {
                if (row.InterfaceLuid == interfaceLuid) {
                    final int err = INSTANCE.DeleteUnicastIpAddressEntry(row);
                    assertNoError(err, "DeleteUnicastIpAddressEntry failed: luid = %s", interfaceLuid);
                }
            }
        } finally {
            INSTANCE.FreeMibTable(table.getPointer());
        }
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
        if (err != WinError.NO_ERROR && err != WinError.ERROR_NOT_FOUND) {
            throw new RuntimeException("Failed to list unicast ip addresses:" + err);
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

        final int err = INSTANCE.GetUnicastIpAddressEntry(row);
        assertNoError(err, "GetUnicastIpAddressEntry failed: luid = %s, address = %s", interfaceLuid, address);
        return row;
    }

    // ------------------------ END UnicastIP related ------------------------


    // ------------------------ START DNS related ------------------------

    private static List<InetAddress> getInterfaceDns(long interfaceLuid, boolean manualSetOnly) {
        return manualSetOnly ? getInterfaceDns0(interfaceLuidToGuid(interfaceLuid)) : getInterfaceDns1(interfaceLuid);
    }

    private static List<InetAddress> getInterfaceDns(GUID interfaceGuid, boolean manualSetOnly) {
        return manualSetOnly ? getInterfaceDns0(interfaceGuid) : getInterfaceDns1(interfaceGuidToLuid(interfaceGuid));
    }

    private static List<InetAddress> getInterfaceDns0(GUID interfaceGuid) {
        final DNS_INTERFACE_SETTINGS dnsInterfaceSettings = new DNS_INTERFACE_SETTINGS();
        dnsInterfaceSettings.Version = DNS_INTERFACE_SETTINGS_VERSION1;
        // dnsInterfaceSettings.QueryAdapterName = DNS_SETTINGS_QUERY_ADAPTER_NAME;
        dnsInterfaceSettings.Flags = DNS_SETTING_NAMESERVER | DNS_SETTING_SEARCHLIST;

        final int err = INSTANCE.GetInterfaceDnsSettings(interfaceGuid, dnsInterfaceSettings);
        assertNoError(err, "GetInterfaceDnsSettings failed: GUID = %s", interfaceGuid.toGuidString());

        INSTANCE.FreeInterfaceDnsSettings(dnsInterfaceSettings.getPointer());

        // Only has a value when manually set
        final String nameServersStr = dnsInterfaceSettings.NameServer;
        final List<InetAddress> nameServers = new LinkedList<>();
        if (null != nameServersStr && !nameServersStr.isEmpty()) {
            final String[] servers = nameServersStr.split(",");
            for (final String server : servers) {
                nameServers.add(toInetAddress(server));
            }
        }
        return nameServers;
    }

    private static InetAddress toInetAddress(final String ipAddressStr) {
        final byte[] addr = NetUtil.createByteArrayFromIpAddressString(ipAddressStr);
        if (null == addr) {
            throw new IllegalStateException("Unknown host: " + ipAddressStr);
        }
        return toInetAddress(addr);
    }

    private static void setInterfaceDns(final GUID interfaceGuid, final int family,
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
            // TODO
            // For < Windows 10 1809
        }
        assertNoError(err, "SetInterfaceDnsSettings failed: GUID = %s", interfaceGuid.toGuidString());
    }

    private static void flushInterfaceDns(final GUID interfaceGuid, final int family) {
        setInterfaceDns(interfaceGuid, family, new InetAddress[0], new String[0]);
    }


    // ------------------------ END DNS related ------------------------


    // ------------------------ START AdapterAddresses related ------------------------

    private static void printInterfaces() {
        final int flags = GAA_FLAG_SKIP_UNICAST | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST
                | GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_FRIENDLY_NAME | GAA_FLAG_INCLUDE_ALL_INTERFACES;
        IP_ADAPTER_ADDRESSES_LH addresses = GetAdaptersAddresses(AF_UNSPEC, flags);
        do {
            final int ifType = addresses.IfType;
            final int ifIndex = addresses.IfIndex;
            final int ifIndex6 = addresses.Ipv6IfIndex;
            final int mtu = addresses.Mtu;
            final String name = addresses.AdapterName.getString(0);
            final WString alias = addresses.FriendlyName;
            final WString desc = addresses.Description;
            final int ipv4Metric = addresses.Ipv4Metric;
            final int ipv6Metric = addresses.Ipv6Metric;
            final String msg = String.format("[%s/%s] [%s] %s:%s:%s -> %s[%s/%s]", ifIndex, ifIndex6, ifType, name, alias, desc, mtu, ipv4Metric, ipv6Metric);

            System.out.println(msg);

            addresses = addresses.Next;
        } while (null != addresses);
    }

    private static List<InetAddress> getInterfaceDns1(final long interfaceLuid) {
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
        /*-
         * GetAdapterAddress
         * - AdapterName: {6CE10339-9804-4AD3-8310-4F81CE0BE645}
         * - FriendlyName: 以太网 2 (alias)
         * - Description: Realtek PCIe GbE Family Controller #2
         */
        return new IP_ADAPTER_ADDRESSES_LH(buffer);
    }

    // ------------------------ END AdapterAddresses related ------------------------

    private static void assertNoError(final int err, final String message, final Object... args) {
        if (WinError.NO_ERROR != err) {
//            throw new Win32Exception(err)
            throw new IllegalStateException("[" + err + "] " + String.format(message, args));
        }
    }

    public static void main(String[] args) throws SocketException, UnknownHostException {
        final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByAlias("以太网 2");
//        nix.addInterfaceAddress(InterfaceAddressEx.of("192.168.1.3", 24));
        for (InterfaceAddressEx interfaceAddress : nix.getInterfaceAddresses()) {
            System.out.println(interfaceAddress);
        }
    }
}