package com.github.pangolin.routing.tun.wintun.win32.iphlp;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winsock2;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public interface IpHelpLib extends IPHlpAPI {
    IpHelpLib INSTANCE = Native.load("IPHlpAPI", IpHelpLib.class, W32APIOptions.DEFAULT_OPTIONS);




    class SOCKADDR_INET extends Union {
        public sockaddr_in Ipv4;
        public sockaddr_in6 Ipv6;
        public int si_family; // TODO might be short?
    }


    // ------------------------ START Interface related ------------------------

    /**
     * The MIB_IPINTERFACE_ROW structure stores interface management information for a particular
     * IP address family on a network interface.
     * <p>
     * Might be wrong, but is good enough to set MTU.
     *
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/ns-netioapi-mib_ipinterface_row">MIB_IPINTERFACE_ROW</a>
     * @see #InitializeIpInterfaceEntry(MIB_IPINTERFACE_ROW)
     * @see #GetIpInterfaceEntry(MIB_IPINTERFACE_ROW)
     * @see #SetIpInterfaceEntry(MIB_IPINTERFACE_ROW)
     */
    @Structure.FieldOrder({
            "Family", "InterfaceLuid", "InterfaceIndex", "MaxReassemblySize", "InterfaceIdentifier",
            "MinRouterAdvertisementInterval", "MaxRouterAdvertisementInterval", "AdvertisingEnabled",
            "ForwardingEnabled", "WeakHostSend", "WeakHostReceive", "UseAutomaticMetric",
            "UseNeighborUnreachabilityDetection", "ManagedAddressConfigurationSupported",
            "OtherStatefulConfigurationSupported", "AdvertiseDefaultRoute", "RouterDiscoveryBehavior",
            "DadTransmits", "BaseReachableTime", "RetransmitTime", "PathMtuDiscoveryTimeout",
            "LinkLocalAddressBehavior", "LinkLocalAddressTimeout", "ZoneIndices", "SitePrefixLength",
            "Metric", "NlMtu", "Connected", "SupportsWakeUpPatterns", "SupportsNeighborDiscovery",
            "SupportsRouterDiscovery", "ReachableTime", "TransmitOffload", "ReceiveOffload",
            "DisableDefaultRoutes"
    })
    class MIB_IPINTERFACE_ROW extends Structure {
        public int Family;
        public long InterfaceLuid;
        public int InterfaceIndex;
        public int MaxReassemblySize;
        public long InterfaceIdentifier;
        public int MinRouterAdvertisementInterval;
        public int MaxRouterAdvertisementInterval;
        public byte AdvertisingEnabled;
        public byte ForwardingEnabled;
        public byte WeakHostSend;
        public byte WeakHostReceive;
        public byte UseAutomaticMetric;
        public byte UseNeighborUnreachabilityDetection;
        public byte ManagedAddressConfigurationSupported;
        public byte OtherStatefulConfigurationSupported;
        public byte AdvertiseDefaultRoute;
        public int RouterDiscoveryBehavior;
        public int DadTransmits;
        public int BaseReachableTime;
        public int RetransmitTime;
        public int PathMtuDiscoveryTimeout;
        public int LinkLocalAddressBehavior;
        public int LinkLocalAddressTimeout;
        public int[] ZoneIndices = new int[SCOPE_LEVEL.scopeLevelCount];
        public int SitePrefixLength;
        public int Metric;
        public int NlMtu;
        public byte Connected;
        public byte SupportsWakeUpPatterns;
        public byte SupportsNeighborDiscovery;
        public byte SupportsRouterDiscovery;
        public int ReachableTime;
        /**
         * 0:NlChecksumSupported
         * 1:NlOptionsSupported
         * 2:TlDatagramChecksumSupported
         * 3:TlStreamChecksumSupported
         * 4:TlStreamOptionsSupported
         * 5:FastPathCompatible
         * 6:TlLargeSendOffloadSupported
         * 7:TlGiantSendOffloadSupported
         */
        public byte TransmitOffload;
        /**
         * 0:NlChecksumSupported
         * 1:NlOptionsSupported
         * 2:TlDatagramChecksumSupported
         * 3:TlStreamChecksumSupported
         * 4:TlStreamOptionsSupported
         * 5:FastPathCompatible
         * 6:TlLargeSendOffloadSupported
         * 7:TlGiantSendOffloadSupported
         */
        public byte ReceiveOffload;
        public byte DisableDefaultRoutes;
    }

    /**
     * Initializes the members of an [MIB_IPINTERFACE_ROW] entry with default values.
     * <p>
     * You must initialize the object using this function, before using it.
     */
    void InitializeIpInterfaceEntry(MIB_IPINTERFACE_ROW row);

    /**
     * Retrieves IP information for the specified interface on the local computer.
     *
     * @param row A pointer to a [MIB_IPINTERFACE_ROW] structure that, on successful return,
     *            receives information for an interface on the local computer. On input, the
     *            [MIB_IPINTERFACE_ROW.InterfaceLuid] or [MIB_IPINTERFACE_ROW.InterfaceIndex] member of the
     *            [MIB_IPINTERFACE_ROW] must be set to the interface for which to retrieve information.
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND]: The interface LUID
     * or interface index doesn't exist.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]:  This error is
     * returned if a NULL pointer is passed in the Row parameter, the [MIB_IPINTERFACE_ROW.Family]
     * is not [IPHlpAPI.AF_INET] or [IPHlpAPI.AF_INET6], or both the InterfaceLuid or
     * InterfaceIndex were unspecified.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: This error is returned
     * if the InterfaceLuid or InterfaceIndex does not match the IP address family.
     * + other: Use [com.sun.jna.platform.win32.Kernel32Util.getLastErrorMessage] to obtain
     */
    int GetIpInterfaceEntry(MIB_IPINTERFACE_ROW row);

    /**
     * Sets the properties of an IP interface on the local computer.
     *
     * @param row A pointer to a [MIB_IPINTERFACE_ROW] structure entry for an interface.
     *            On input, the [MIB_IPINTERFACE_ROW.Family] must be set to [IPHlpAPI.AF_INET6]
     *            or [IPHlpAPI.AF_INET] and the [MIB_IPINTERFACE_ROW.InterfaceLuid] or the
     *            [MIB_IPINTERFACE_ROW.InterfaceIndex] must be specified. On a successful return,
     *            the [MIB_IPINTERFACE_ROW.InterfaceLuid] is filled in if [MIB_IPINTERFACE_ROW.InterfaceIndex]
     *            was specified.
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Access is denied. Need run as admin.
     * + see [GetIpInterfaceEntry]
     */
    int SetIpInterfaceEntry(MIB_IPINTERFACE_ROW row);

    // ------------------------- END Interface related -------------------------

    // ------------------------ START UnicastIP related ------------------------

    /**
     * The MIB_UNICASTIPADDRESS_ROW structure stores information about a unicast IP address.
     *
     * @see #InitializeUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW)
     * @see #GetUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW)
     * @see #CreateUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW)
     * @see #SetUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW)
     * @see #DeleteUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW)
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/ns-netioapi-mib_unicastipaddress_row">MIB_UNICASTIPADDRESS_ROW</a>
     */
    @Structure.FieldOrder({
            "Address", "InterfaceLuid", "InterfaceIndex", "PrefixOrigin", "SuffixOrigin",
            "ValidLifetime", "PreferredLifetime", "OnLinkPrefixLength", "SkipAsSource",
            "DadState", "ScopeId", "CreationTimeStamp",
    })
    class MIB_UNICASTIPADDRESS_ROW extends Structure {
        public static class ByReference extends MIB_UNICASTIPADDRESS_ROW implements Structure.ByReference {}

        public SOCKADDR_INET Address;
        public long InterfaceLuid;
        public int InterfaceIndex;
        public int PrefixOrigin;
        public int SuffixOrigin;
        public int ValidLifetime;
        public int PreferredLifetime;
        /**
         * MASK.
         * E.g: 192.168.0.0/16, OnLinkPrefixLength is 16.
         */
        public byte OnLinkPrefixLength;
        public byte SkipAsSource;
        public byte DadState;
        public int ScopeId;
        public long CreationTimeStamp;
    }

    /**
     * The MIB_UNICASTIPADDRESS_TABLE structure contains a table of unicast IP address entries.
     * <p>
     * Might be wrong, but is good enough to set MTU.
     *
     * @see #GetUnicastIpAddressTable(int, PointerByReference)
     * @see #FreeMibTable(Pointer)
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/ns-netioapi-mib_unicastipaddress_table">MIB_UNICASTIPADDRESS_TABLE</a>
     */
    @Structure.FieldOrder({"NumEntries", "Table"})
    class MIB_UNICASTIPADDRESS_TABLE extends Structure {
        public int NumEntries;
        public MIB_UNICASTIPADDRESS_ROW[] Table = new MIB_UNICASTIPADDRESS_ROW[1];

        public MIB_UNICASTIPADDRESS_TABLE() {
            super();
        }

        public MIB_UNICASTIPADDRESS_TABLE(Pointer p) {
            super(p);
            this.Table = new MIB_UNICASTIPADDRESS_ROW[p.getInt(0)];
            read();
        }
    }

    /**
     * Initializes a [MIB_UNICASTIPADDRESS_ROW] structure with default values for
     * an unicast IP address entry on the local computer.
     * <p>
     * You must initialize the object using this function, before using it.
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-initializeunicastipaddressentry">InitializeUnicastIpAddressEntry</a>
     */
    void InitializeUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW row);

    /**
     * Retrieves information for an existing unicast IP address entry on the local computer.
     *
     * @param row A pointer to a [MIB_UNICASTIPADDRESS_ROW] structure entry for an
     *            unicast IP address entry. The [MIB_UNICASTIPADDRESS_ROW.InterfaceLuid] or [MIB_UNICASTIPADDRESS_ROW.InterfaceIndex]
     *            must be initialized, and the [MIB_UNICASTIPADDRESS_ROW.Address] must be a
     *            valid IPv4 or IPv6 Address. On successful return, this structure will be
     *            updated with the properties for an existing unicast IP address.
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND]: Invalid luid or index.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Missing luid and index, or invalid address.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not match the ip.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     */
    int GetUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW row);

    /**
     * Adds a new unicast IP address entry on the local computer.
     *
     * @param row A pointer to [MIB_UNICASTIPADDRESS_ROW].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-createunicastipaddressentry#return-value
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + [com.sun.jna.platform.win32.WinError.ERROR_OBJECT_ALREADY_EXISTS]: The address already exists.
     * + other
     */
    int CreateUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW row);

    /**
     * Sets/updates the properties of an existing unicast IP address entry on the local computer.
     *
     * @param row A pointer to [MIB_UNICASTIPADDRESS_ROW].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setunicastipaddressentry#return-value
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     */
    int SetUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW row);

    /**
     * Deletes an existing unicast IP address entry on the local computer.
     *
     * @param row A pointer to [MIB_UNICASTIPADDRESS_ROW].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-deleteunicastipaddressentry#return-value
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     */
    int DeleteUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW row);

    /**
     * Retrieves the unicast IP address table on the local computer.
     *
     * @param family Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @param table  The pointer of the pointer to [info.skyblond.jna.iphlp.MIB_UNICASTIPADDRESS_TABLE].
     *               The pointer must be released by [FreeMibTable].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Invalid family.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_ENOUGH_MEMORY]: Insufficient memory.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: No unicast ip found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     */
    int GetUnicastIpAddressTable(int family, MIB_UNICASTIPADDRESS_TABLE table);

    /**
     * Frees the buffer allocated by the functions that return tables of network
     * interfaces, addresses, and routes.
     *
     * @param pointer The pointer to free.
     */
    void FreeMibTable(Pointer pointer);

    // ------------------------- END UnicastIP related -------------------------

    int GAA_FLAG_SKIP_UNICAST = 0x0001;
    int GAA_FLAG_SKIP_ANYCAST = 0x0002;
    int GAA_FLAG_SKIP_MULTICAST = 0x0004;
    int GAA_FLAG_SKIP_DNS_SERVER = 0x0008;
    int GAA_FLAG_INCLUDE_PREFIX = 0x0010;
    int GAA_FLAG_SKIP_FRIENDLY_NAME = 0x0020;
    int GAA_FLAG_INCLUDE_WINS_INFO = 0x0040;
    int GAA_FLAG_INCLUDE_GATEWAYS = 0x0080;
    int GAA_FLAG_INCLUDE_ALL_INTERFACES = 0x0100;
    int GAA_FLAG_INCLUDE_ALL_COMPARTMENTS = 0x0200;
    int GAA_FLAG_INCLUDE_TUNNEL_BINDINGORDER = 0x0400;

    @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr_in extends Structure {
        public sockaddr_in() {
        }

        public sockaddr_in(final Pointer p) {
            super(p);
            read();
        }

        public short sin_family;
        public short sin_port;
        public byte[] sin_addr = new byte[4];
        public byte[] sin_zero = new byte[8];
    }

    @Structure.FieldOrder({"sin6_family", "sin6_port", "sin6_flowinfo", "sin6_addr", "sin6_scope_id"})
    class sockaddr_in6 extends Structure {

        public sockaddr_in6(Pointer p) {
            super(p);
            read();
        }

        public short sin6_family;
        public short sin6_port;
        public int sin6_flowinfo;
        public byte[] sin6_addr = new byte[16];
        public int sin6_scope_id;
    }

    /**
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/ws2def/ns-ws2def-socket_address">SOCKET_ADDRESS</a>
     */
    @Structure.FieldOrder({"lpSockaddr", "iSockaddrLength"})
    class SOCKET_ADDRESS extends Structure {
        public Pointer lpSockaddr;
        public int iSockaddrLength;

        public InetAddress toAddress() throws UnknownHostException {
            switch (lpSockaddr.getShort(0)) {
                case AF_INET:
                    sockaddr_in in4 = new sockaddr_in(lpSockaddr);
                    return InetAddress.getByAddress(in4.sin_addr);
                case AF_INET6:
                    sockaddr_in6 in6 = new sockaddr_in6(lpSockaddr);
                    return Inet6Address.getByAddress("", in6.sin6_addr, in6.sin6_scope_id);
            }
            return null;
        }
    }

    /**
     * The IP_ADAPTER_UNICAST_ADDRESS structure stores a single unicast IP address in a linked list
     * of IP addresses for a particular adapter.
     *
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/iptypes/ns-iptypes-ip_adapter_unicast_address_lh">IP_ADAPTER_UNICAST_ADDRESS_LH</a>
     */
    @Structure.FieldOrder({
            "Length", "IfIndex", "Next", "Address", "PrefixOrigin", "SuffixOrigin",
            "DadState", "ValidLifetime", "PreferredLifetime", "LeaseLifetime", "OnLinkPrefixLength"
    })
    class IP_ADAPTER_UNICAST_ADDRESS_LH extends Structure {
        public static class ByReference
                extends IP_ADAPTER_UNICAST_ADDRESS_LH implements Structure.ByReference {
        }

        public int Length;
        public int IfIndex;

        public IP_ADAPTER_UNICAST_ADDRESS_LH.ByReference Next;
        public SOCKET_ADDRESS Address;
        public int PrefixOrigin;
        public int SuffixOrigin;
        public int DadState;
        public int ValidLifetime;
        public int PreferredLifetime;
        public int LeaseLifetime;
        public byte OnLinkPrefixLength;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_ANYCAST_ADDRESS_XP extends Structure {
        public static class ByReference
                extends IP_ADAPTER_ANYCAST_ADDRESS_XP implements Structure.ByReference {
        }

        public int Length;
        public int Reserved;
        public IP_ADAPTER_ANYCAST_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_MULTICAST_ADDRESS_XP extends Structure {
        public static class ByReference
                extends IP_ADAPTER_MULTICAST_ADDRESS_XP implements Structure.ByReference {
        }

        public int Length;
        public int Reserved;
        public IP_ADAPTER_MULTICAST_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_DNS_SERVER_ADDRESS_XP extends Structure {
        public static class ByReference
                extends IP_ADAPTER_DNS_SERVER_ADDRESS_XP implements Structure.ByReference {
        }

        public int Length;
        public int Reserved;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_GATEWAY_ADDRESS_LH extends Structure {
        public static class ByReference
                extends IP_ADAPTER_GATEWAY_ADDRESS_LH implements Structure.ByReference {
        }

        public int Length;
        public int Reserved;
        public IP_ADAPTER_GATEWAY_ADDRESS_LH.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Next", "_String"})
    class IP_ADAPTER_DNS_SUFFIX extends Structure {
        public static class ByReference
                extends IP_ADAPTER_DNS_SUFFIX implements Structure.ByReference {
        }

        public IP_ADAPTER_DNS_SUFFIX.ByReference Next;
        public char[] _String = new char[256];
    }

    @Structure.FieldOrder({
            "Length", "IfIndex", "Next", "AdapterName", "FirstUnicastAddress", "FirstAnycastAddress",
            "FirstMulticastAddress", "FirstDnsServerAddress", "DnsSuffix", "Description", "FriendlyName",
            "PhysicalAddress", "PhysicalAddressLength", "Flags", "Mtu", "IfType", "OperStatus", "Ipv6IfIndex",
            "ZoneIndices", "FirstPrefix", "TransmitLinkSpeed", "ReceiveLinkSpeed", "FirstWinsServerAddress",
            "FirstGatewayAddress", "Ipv4Metric", "Ipv6Metric", "Luid", "Dhcpv4Server",
            "CompartmentId", "NetworkGuid", "ConnectionType", "TunnelType", "Dhcpv6Server",
            "Dhcpv6ClientDuid", "Dhcpv6ClientDuidLength", "Dhcpv6Iaid", "FirstDnsSuffix",
    })
    class IP_ADAPTER_ADDRESSES_LH extends Structure {
        public static class ByReference
                extends IP_ADAPTER_ADDRESSES_LH implements Structure.ByReference {
        }

        public IP_ADAPTER_ADDRESSES_LH() {
        }

        public IP_ADAPTER_ADDRESSES_LH(Pointer p) {
            super(p);
            read();
        }


        public int Length;
        public int IfIndex;

        public IP_ADAPTER_ADDRESSES_LH.ByReference Next;
        public String AdapterName;
        public IP_ADAPTER_UNICAST_ADDRESS_LH.ByReference FirstUnicastAddress;
        public IP_ADAPTER_ANYCAST_ADDRESS_XP.ByReference FirstAnycastAddress;
        public IP_ADAPTER_MULTICAST_ADDRESS_XP.ByReference FirstMulticastAddress;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference FirstDnsServerAddress;
        public WString DnsSuffix;
        public WString Description;
        public WString FriendlyName;
        public byte[] PhysicalAddress = new byte[8];
        public int PhysicalAddressLength;

        public int Flags;

        public int Mtu;
        public int IfType;
        public int OperStatus;
        public int Ipv6IfIndex;
        public int[] ZoneIndices = new int[16];
        public Pointer FirstPrefix;
        public long TransmitLinkSpeed;
        public long ReceiveLinkSpeed;

        public Pointer FirstWinsServerAddress;
        public IP_ADAPTER_GATEWAY_ADDRESS_LH.ByReference FirstGatewayAddress;

        public int Ipv4Metric;
        public int Ipv6Metric;
        public long Luid;
        public SOCKET_ADDRESS Dhcpv4Server;
        public int CompartmentId;
        public Guid.GUID NetworkGuid;
        public int ConnectionType;
        public int TunnelType;
        public SOCKET_ADDRESS Dhcpv6Server;
        public byte[] Dhcpv6ClientDuid = new byte[130];
        public int Dhcpv6ClientDuidLength;
        public int Dhcpv6Iaid;
        public IP_ADAPTER_DNS_SUFFIX.ByReference FirstDnsSuffix;
    }

    int GetAdaptersAddresses(int family, int flags, Pointer reserved, Pointer adapterAddresses, IntByReference sizeOfPointer);







    interface SCOPE_LEVEL {
        int scopeLevelInterface = 1;
        int scopeLevelLink = 2;
        int scopeLevelSubnet = 3;
        int scopeLevelAdmin = 4;
        int scopeLevelSite = 5;
        int scopeLevelOrganization = 8;
        int scopeLevelGlobal = 14;
        int scopeLevelCount = 16;
    }

    /**
     * DNS_INTERFACE_SETTINGS.
     */
    int DNS_INTERFACE_SETTINGS_VERSION1 = 1;
    /**
     * DNS_INTERFACE_SETTINGS_EX.
     */
    int DNS_INTERFACE_SETTINGS_VERSION2 = 2;
    /**
     * DNS_INTERFACE_SETTINGS3.
     */
    int DNS_INTERFACE_SETTINGS_VERSION3 = 3;

    int DNS_SETTING_IPV6 = 0x0001;
    int DNS_SETTING_NAMESERVER = 0x0002;
    int DNS_SETTING_SEARCHLIST = 0x0004;
    int DNS_SETTING_REGISTRATION_ENABLED = 0x0008;
    int DNS_SETTING_DOMAIN = 0x0020;
    int DNS_SETTINGS_ENABLE_LLMNR = 0x0080;
    int DNS_SETTINGS_QUERY_ADAPTER_NAME = 0x0100;
    int DNS_SETTING_PROFILE_NAMESERVER = 0x0200;

    @Structure.FieldOrder({
            "Version",
            "Flags",
            "Domain",
            "NameServer",
            "SearchList",
            "RegistrationEnabled",
            "RegisterAdapterName",
            "EnableLLMNR",
            "QueryAdapterName",
            "ProfileNameServer"
    })
    class DNS_INTERFACE_SETTINGS extends Structure {
        public static class ByReference
                extends DNS_INTERFACE_SETTINGS implements Structure.ByReference {
        }

        public int Version = DNS_INTERFACE_SETTINGS_VERSION1;
        public long Flags;
        public String Domain;
        public String NameServer;
        public String SearchList;
        public int RegistrationEnabled;
        public int RegisterAdapterName;
        public int EnableLLMNR;
        public int QueryAdapterName;
        public String ProfileNameServer;
    }

    /**
     *
     * @param Interface
     * @param Settings
     * @throws LastErrorException
     * @see <a href="https://learn.microsoft.com/zh-cn/windows/win32/api/netioapi/nf-netioapi-setinterfacednssettings"></a>
     */
    int SetInterfaceDnsSettings(Guid.GUID Interface, DNS_INTERFACE_SETTINGS Settings) throws LastErrorException;

    void GetInterfaceDnsSettings(Guid.GUID Interface, DNS_INTERFACE_SETTINGS settings) throws LastErrorException;

    void FreeInterfaceDnsSettings(Pointer dnsSettings) throws LastErrorException;

    void ConvertInterfaceLuidToGuid(LongByReference luid, Guid.GUID.ByReference guid)throws LastErrorException;
}
