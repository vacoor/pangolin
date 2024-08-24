package com.github.pangolin.routing.tun.wintun.win32;

import com.sun.jna.*;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static com.sun.jna.platform.win32.Guid.GUID;

public interface IpHelpLib extends IPHlpAPI {
    IpHelpLib INSTANCE = Native.load("IPHlpAPI", IpHelpLib.class, W32APIOptions.DEFAULT_OPTIONS);

    int NDIS_IF_MAX_STRING_SIZE = 256;

    /**
     * Converts an interface alias name for a network interface to the locally unique identifier (LUID) for the interface.
     *
     * @param InterfaceAlias A pointer to a NULL-terminated Unicode string containing the alias name of the network interface.
     * @param InterfaceLuid  A pointer to the NET_LUID for this interface.
     * @return On success, ConvertInterfaceAliasToLuid returns NO_ERROR. Any nonzero return value indicates failure
     * and a NULL is returned in the InterfaceLuid parameter.
     * <ul><li>[com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter</li></ul>
     */
    int ConvertInterfaceAliasToLuid(String InterfaceAlias, LongByReference InterfaceLuid) throws LastErrorException;

    /**
     * Converts a Unicode network interface name to the locally unique identifier (LUID) for the interface.
     *
     * @param InterfaceName A pointer to a NULL-terminated Unicode string containing the network interface name.
     * @param InterfaceLuid A pointer to the NET_LUID for this interface.
     */
    int ConvertInterfaceNameToLuidW(final String InterfaceName, final LongByReference InterfaceLuid) throws LastErrorException;

    /**
     * Converts a globally unique identifier (GUID) for a network interface to the locally unique identifier (LUID) for the interface.
     *
     * @param InterfaceGuid A pointer to a GUID for a network interface.
     * @param InterfaceLuid A pointer to the NET_LUID for this interface.
     */
    int ConvertInterfaceGuidToLuid(GUID.ByReference InterfaceGuid, LongByReference InterfaceLuid) throws LastErrorException;

    /**
     * Converts a local index for a network interface to the locally unique identifier (LUID) for the interface.
     *
     * @param InterfaceIndex The local index value for a network interface.
     * @param InterfaceLuid  A pointer to the NET_LUID for this interface.
     */
    int ConvertInterfaceIndexToLuid(int InterfaceIndex, LongByReference InterfaceLuid) throws LastErrorException;

    /**
     * Converts a locally unique identifier (LUID) for a network interface to an interface alias.
     *
     * @param InterfaceLuid  A pointer to a NET_LUID for a network interface.
     * @param InterfaceAlias A pointer to a buffer to hold the NULL-terminated Unicode string containing
     *                       the alias name of the network interface when the function returns successfully.
     * @param Length         The length, in characters, of the buffer pointed to by the InterfaceAlias parameter.
     *                       This value must be large enough to accommodate the alias name of the network
     *                       interface and the terminating NULL character. The maximum required length is
     *                       NDIS_IF_MAX_STRING_SIZE + 1.
     */
    int ConvertInterfaceLuidToAlias(LongByReference InterfaceLuid, char[] InterfaceAlias, int Length) throws LastErrorException;

    /**
     * Converts a locally unique identifier (LUID) for a network interface
     * to a globally unique identifier (GUID) for the interface.
     *
     * @param InterfaceLuid A pointer to a NET_LUID for a network interface.
     * @param InterfaceGuid A pointer to the GUID for this interface.
     * @return On success, ConvertInterfaceLuidToGuid returns NO_ERROR.
     * Any nonzero return value indicates failure and a NULL is returned in the InterfaceGuid parameter.
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-convertinterfaceluidtoguid">ConvertInterfaceLuidToGuid</a>
     */
    int ConvertInterfaceLuidToGuid(LongByReference InterfaceLuid, GUID.ByReference InterfaceGuid) throws LastErrorException;

    /**
     * Converts a locally unique identifier (LUID) for a network interface to the local index for the interface.
     *
     * @param InterfaceLuid  A pointer to a NET_LUID for a network interface.
     * @param InterfaceIndex The local index value for the interface.
     */
    int ConvertInterfaceLuidToIndex(LongByReference InterfaceLuid, IntByReference InterfaceIndex) throws LastErrorException;

    /**
     * Converts a locally unique identifier (LUID) for a network interface to the Unicode interface name.
     *
     * @param InterfaceLuid A pointer to a NET_LUID for a network interface.
     * @param InterfaceName A pointer to a buffer to hold the NULL-terminated Unicode string containing
     *                      the interface name when the function returns successfully.
     * @param Length        The number of characters in the array pointed to by the InterfaceName parameter.
     *                      This value must be large enough to accommodate the interface name and the
     *                      terminating null character. The maximum required length is NDIS_IF_MAX_STRING_SIZE + 1.
     */
    int ConvertInterfaceLuidToNameW(LongByReference InterfaceLuid, char[] InterfaceName, int Length) throws LastErrorException;


    // ------------------------ START Interface related ------------------------

    /**
     * The MIB_IPINTERFACE_ROW structure stores interface management information for a particular
     * IP address family on a network interface.
     * <p>
     * Might be wrong, but is good enough to set MTU.
     *
     * @see #InitializeIpInterfaceEntry(MIB_IPINTERFACE_ROW)
     * @see #GetIpInterfaceEntry(MIB_IPINTERFACE_ROW)
     * @see #SetIpInterfaceEntry(MIB_IPINTERFACE_ROW)
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/ns-netioapi-mib_ipinterface_row">MIB_IPINTERFACE_ROW</a>
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
        public int[] ZoneIndices = new int[16];
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
     * Initializes the members of an [MibIPInterfaceRow] entry with default values.
     * <p>
     * You must initialize the object using this function, before using it.
     *
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-initializeipinterfaceentry">InitializeIpInterfaceEntry</a>
     */
    void InitializeIpInterfaceEntry(MIB_IPINTERFACE_ROW Row) throws LastErrorException;

    /**
     * Retrieves IP information for the specified interface on the local computer.
     *
     * @param Row A pointer to a [MibIPInterfaceRow] structure that, on successful return,
     *            receives information for an interface on the local computer. On input, the
     *            [MibIPInterfaceRow.InterfaceLuid] or [MibIPInterfaceRow.InterfaceIndex] member of the
     *            [MibIPInterfaceRow] must be set to the interface for which to retrieve information.
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * <ul><li>[com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND]: The interface LUID
     * or interface index doesn't exist.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]:  This error is
     * returned if a NULL pointer is passed in the Row parameter, the [MibIPInterfaceRow.Family]
     * is not [IPHlpAPI.AF_INET] or [IPHlpAPI.AF_INET6], or both the InterfaceLuid or
     * InterfaceIndex were unspecified.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: This error is returned
     * if the InterfaceLuid or InterfaceIndex does not match the IP address family.
     * </li><li>other: Use [com.sun.jna.platform.win32.Kernel32Util.getLastErrorMessage] to obtain</li></ul>
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-getipinterfaceentry">GetIpInterfaceEntry</a>
     */
    int GetIpInterfaceEntry(MIB_IPINTERFACE_ROW Row) throws LastErrorException;

    /**
     * Sets the properties of an IP interface on the local computer.
     *
     * @param Row A pointer to a [MibIPInterfaceRow] structure entry for an interface.
     *            On input, the [MibIPInterfaceRow.Family] must be set to [IPHlpAPI.AF_INET6]
     *            or [IPHlpAPI.AF_INET] and the [MibIPInterfaceRow.InterfaceLuid] or the
     *            [MibIPInterfaceRow.InterfaceIndex] must be specified. On a successful return,
     *            the [MibIPInterfaceRow.InterfaceLuid] is filled in if [MibIPInterfaceRow.InterfaceIndex]
     *            was specified.
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * <ul><li>[com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Access is denied. Need run as admin.</li></ul>
     * @see #GetIpInterfaceEntry
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setipinterfaceentry">SetIpInterfaceEntry</a>
     */
    int SetIpInterfaceEntry(MIB_IPINTERFACE_ROW Row) throws LastErrorException;


    // ------------------------- END Interface related -------------------------


    // ------------------------ START UnicastIP related ------------------------

    @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr_in extends Structure {
        public sockaddr_in() {
        }

        public sockaddr_in(Pointer p) {
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

        public sockaddr_in6() {
        }

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

    class SOCKADDR_INET extends Union {
        public sockaddr_in Ipv4;
        public sockaddr_in6 Ipv6;
        public int si_family; // TODO might be short?
    }

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
        //        public MIB_UNICASTIPADDRESS_ROW[] Table = new MIB_UNICASTIPADDRESS_ROW[1];
        public MIB_UNICASTIPADDRESS_ROW[] Table = new MIB_UNICASTIPADDRESS_ROW[0];

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
     * Retrieves the unicast IP address table on the local computer.
     *
     * @param Family Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @param Table  The pointer of the pointer to [info.skyblond.jna.iphlp.MIB_UNICASTIPADDRESS_TABLE].
     *               The pointer must be released by [FreeMibTable].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * <ul><li>[com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Invalid family.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_NOT_ENOUGH_MEMORY]: Insufficient memory.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: No unicast ip found.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * </li><li>other</li></ul>
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-getunicastipaddresstable">GetUnicastIpAddressTable</a>
     */
    int GetUnicastIpAddressTable(int Family, PointerByReference Table) throws LastErrorException;

    /**
     * Frees the buffer allocated by the functions that return tables of network
     * interfaces, addresses, and routes.
     *
     * @param Memory A pointer to the buffer to free..
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-freemibtable">FreeMibTable</a>
     */
    void FreeMibTable(Pointer Memory) throws LastErrorException;

    /**
     * Initializes a [MIB_UNICASTIPADDRESS_ROW] structure with default values for
     * an unicast IP address entry on the local computer.
     * <p>
     * You must initialize the object using this function, before using it.
     *
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-initializeunicastipaddressentry">InitializeUnicastIpAddressEntry</a>
     */
    void InitializeUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW row) throws LastErrorException;

    /**
     * Retrieves information for an existing unicast IP address entry on the local computer.
     *
     * @param Row A pointer to a [MIB_UNICASTIPADDRESS_ROW] structure entry for an
     *            unicast IP address entry. The [MIB_UNICASTIPADDRESS_ROW.InterfaceLuid] or [MIB_UNICASTIPADDRESS_ROW.InterfaceIndex]
     *            must be initialized, and the [MIB_UNICASTIPADDRESS_ROW.Address] must be a
     *            valid IPv4 or IPv6 Address. On successful return, this structure will be
     *            updated with the properties for an existing unicast IP address.
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * <ul><li>[com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND]: Invalid luid or index.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Missing luid and index, or invalid address.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not match the ip.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * </li><li>other</li></ul>
     */
    int GetUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW Row) throws LastErrorException;

    /**
     * Adds a new unicast IP address entry on the local computer.
     *
     * @param Row A pointer to [MIB_UNICASTIPADDRESS_ROW].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * <ul><li> [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-createunicastipaddressentry#return-value
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_OBJECT_ALREADY_EXISTS]: The address already exists.
     * </li><li> other</li></ul>
     */
    int CreateUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW Row) throws LastErrorException;

    /**
     * Sets/updates the [properties] of an existing unicast IP address entry on the local computer.
     *
     * @param Row A pointer to [MIB_UNICASTIPADDRESS_ROW].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * <ul><li> [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setunicastipaddressentry#return-value
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * </li><li> other</li></ul>
     */
    int SetUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW Row) throws LastErrorException;

    /**
     * Deletes an existing unicast IP address entry on the local computer.
     *
     * @param Row A pointer to [MIB_UNICASTIPADDRESS_ROW].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * <ul><li> [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-deleteunicastipaddressentry#return-value
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * </li><li> [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * </li><li> other</li></ul>
     */
    int DeleteUnicastIpAddressEntry(MIB_UNICASTIPADDRESS_ROW Row) throws LastErrorException;


    // ------------------------- END UnicastIP related -------------------------


    // ------------------------ START DNS related ------------------------


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

    /**
     * Configures the interface settings only for the IPv6 networking stack.
     * If this option is set, then any IP addresses specified in the NameServer or ProfileNameServer
     * members must be IPv6 addresses. By default, the DNS interface settings specified in this structure
     * are applied only to the IPv4 networking stack.
     */
    int DNS_SETTING_IPV6 = 0x0001;

    /**
     * Configures static adapter DNS servers on the specified interface via the NameServer member.
     */
    int DNS_SETTING_NAMESERVER = 0x0002;

    /**
     * Configures the connection-specific DNS suffix search list for the given adapter via the SearchList member.
     */
    int DNS_SETTING_SEARCHLIST = 0x0004;

    /**
     * Enables or disables the dynamic DNS registration for the given adapter.
     * This is system-enabled by default.
     */
    int DNS_SETTING_REGISTRATION_ENABLED = 0x0008;

    /**
     * Configures the connection-specific DNS suffix for the given adapter via the Domain member.
     */
    int DNS_SETTING_DOMAIN = 0x0020;

    /**
     * Enables or disables name resolution using LLMNR and mDNS on the specified adapter.
     * This is system-enabled by default.
     */
    int DNS_SETTINGS_ENABLE_LLMNR = 0x0080;

    /**
     * Enables or disables the use of the adapter name as a suffix for DNS queries.
     * This is system-enabled by default.
     */
    int DNS_SETTINGS_QUERY_ADAPTER_NAME = 0x0100;

    /**
     * Configures static profile DNS servers on the specified interface via the ProfileNameServer member.
     */
    int DNS_SETTING_PROFILE_NAMESERVER = 0x0200;

    @Structure.FieldOrder({
            "Version", "Flags", "Domain", "NameServer", "SearchList", "RegistrationEnabled",
            "RegisterAdapterName", "EnableLLMNR", "QueryAdapterName", "ProfileNameServer"
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
     * Retrieves the DNS settings from the interface specified in the Interface parameter.
     * <p>
     * When you are done with the returned settings object, you must call FreeInterfaceDnsSettings to free it.
     *
     * @param Interface The GUID of the COM interface that the settings refer to.
     * @param Settings  GetInterfaceDnsSettings populates all the settings in this structure.
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-getinterfacednssettings">GetInterfaceDnsSettings</a>
     */
    int GetInterfaceDnsSettings(GUID Interface, DNS_INTERFACE_SETTINGS Settings) throws LastErrorException;

    /**
     * Frees the settings object returned by GetInterfaceDnsSettings.
     *
     * @param Settings The settings object returned by GetInterfaceDnsSettings.
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-freeinterfacednssettings">FreeInterfaceDnsSettings</a>
     */
    void FreeInterfaceDnsSettings(Pointer Settings) throws LastErrorException;

    /**
     * Sets the per-interface DNS settings specified in the Settings parameter.
     *
     * @param Interface The GUID of the COM interface that the settings refer to.
     * @param Settings  A pointer to a DNS_INTERFACE_SETTINGS-type structure that contains the DNS interface settings.
     * @return Returns NO_ERROR if successful. A non-zero return value indicates failure.
     * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setinterfacednssettings">SetInterfaceDnsSettings</a>
     */
    int SetInterfaceDnsSettings(GUID Interface, DNS_INTERFACE_SETTINGS Settings) throws LastErrorException;


    // ------------------------ END DNS related ------------------------


    // ------------------------ START AdapterAddresses related ------------------------


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

    /**
     * Do not return unicast addresses.
     */
    int GAA_FLAG_SKIP_UNICAST = 0x0001;

    /**
     * Do not return IPv6 anycast addresses.
     */
    int GAA_FLAG_SKIP_ANYCAST = 0x0002;

    /**
     * Do not return multicast addresses.
     */
    int GAA_FLAG_SKIP_MULTICAST = 0x0004;

    /**
     * Do not return addresses of DNS servers.
     */
    int GAA_FLAG_SKIP_DNS_SERVER = 0x0008;

    /**
     * Return a list of IP address prefixes on this adapter.
     * <p>
     * When this flag is set, IP address prefixes are returned for both IPv6 and IPv4 addresses.
     * This flag is supported on Windows XP with SP1 and later.
     */
    int GAA_FLAG_INCLUDE_PREFIX = 0x0010;

    /**
     * Do not return the adapter friendly name.
     */
    int GAA_FLAG_SKIP_FRIENDLY_NAME = 0x0020;

    /**
     * Return addresses of Windows Internet Name Service (WINS) servers.
     * This flag is supported on Windows Vista and later.
     */
    int GAA_FLAG_INCLUDE_WINS_INFO = 0x0040;

    /**
     * This flag is supported on Windows Vista and later.
     */
    int GAA_FLAG_INCLUDE_GATEWAYS = 0x0080;

    /**
     * Return addresses for all NDIS interfaces.
     * This flag is supported on Windows Vista and later.
     */
    int GAA_FLAG_INCLUDE_ALL_INTERFACES = 0x0100;

    /**
     * Return addresses in all routing compartments.
     * This flag is not currently supported and reserved for future use.
     */
    int GAA_FLAG_INCLUDE_ALL_COMPARTMENTS = 0x0200;

    /**
     * Return the adapter addresses sorted in tunnel binding order.
     * This flag is supported on Windows Vista and later.
     */
    int GAA_FLAG_INCLUDE_TUNNEL_BINDINGORDER = 0x0400;

    /**
     * Retrieves the addresses associated with the adapters on the local computer.
     *
     * @param Family           Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @param Flags            The type of addresses to retrieve.
     * @param Reserved         This parameter is not currently used, but is reserved for future system use.
     *                         The calling application should pass NULL for this parameter.
     * @param AdapterAddresses A pointer to a buffer that contains a linked list of IP_ADAPTER_ADDRESSES
     *                         structures on successful return.
     * @param SizePointer      A pointer to a variable that specifies the size of the buffer pointed to by AdapterAddresses.
     * @return If the function succeeds, the return value is ERROR_SUCCESS (defined to the same value as NO_ERROR).
     * If the function fails, the return value is one of the following error codes:
     * <ul><li>[com.sun.jna.platform.win32.WinError.ERROR_ADDRESS_NOT_ASSOCIATED]: An address has not yet been
     * associated with the network endpoint. DHCP lease information was available.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_BUFFER_OVERFLOW]: The buffer size indicated by the
     * SizePointer parameter is too small to hold the adapter information or the AdapterAddresses parameter is
     * NULL. The SizePointer parameter returned points to the required size of the buffer to hold the adapter
     * information.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Invalid family.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_NOT_ENOUGH_MEMORY]: Insufficient memory.
     * </li><li>[com.sun.jna.platform.win32.WinError.ERROR_NO_DATA]: No addresses were found for the requested parameters..
     * </li><li>other</li></ul>
     */
    int GetAdaptersAddresses(int Family, int Flags, Pointer Reserved,
                             Pointer AdapterAddresses, IntByReference SizePointer) throws LastErrorException;


    // ------------------------ END AdapterAddresses related ------------------------

}
