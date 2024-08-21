package com.github.pangolin.routing.beta.tun.jna.win32;

import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibIPInterfaceRow;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibUnicastIPAddressRow;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public interface IpHelpLib extends IPHlpAPI {
    IpHelpLib INSTANCE = Native.load("IPHlpAPI", IpHelpLib.class, W32APIOptions.DEFAULT_OPTIONS);

    // ------------------------ START Interface related ------------------------

    /**
     * Initializes the members of an [MibIPInterfaceRow] entry with default values.
     *
     * You must initialize the object using this function, before using it.
     * */
    void InitializeIpInterfaceEntry(MibIPInterfaceRow row);

    /**
     * Retrieves IP information for the specified interface on the local computer.
     *
     * @param row A pointer to a [MibIPInterfaceRow] structure that, on successful return,
     * receives information for an interface on the local computer. On input, the
     * [MibIPInterfaceRow.InterfaceLuid] or [MibIPInterfaceRow.InterfaceIndex] member of the
     * [MibIPInterfaceRow] must be set to the interface for which to retrieve information.
     *
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND]: The interface LUID
     * or interface index doesn't exist.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]:  This error is
     * returned if a NULL pointer is passed in the Row parameter, the [MibIPInterfaceRow.Family]
     * is not [IPHlpAPI.AF_INET] or [IPHlpAPI.AF_INET6], or both the InterfaceLuid or
     * InterfaceIndex were unspecified.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: This error is returned
     * if the InterfaceLuid or InterfaceIndex does not match the IP address family.
     * + other: Use [com.sun.jna.platform.win32.Kernel32Util.getLastErrorMessage] to obtain
     * */
    int GetIpInterfaceEntry(MibIPInterfaceRow row);

    /**
     * Sets the properties of an IP interface on the local computer.
     *
     * @param row A pointer to a [MibIPInterfaceRow] structure entry for an interface.
     * On input, the [MibIPInterfaceRow.Family] must be set to [IPHlpAPI.AF_INET6]
     * or [IPHlpAPI.AF_INET] and the [MibIPInterfaceRow.InterfaceLuid] or the
     * [MibIPInterfaceRow.InterfaceIndex] must be specified. On a successful return,
     * the [MibIPInterfaceRow.InterfaceLuid] is filled in if [MibIPInterfaceRow.InterfaceIndex]
     * was specified.
     *
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Access is denied. Need run as admin.
     * + see [GetIpInterfaceEntry]
     * */
    int SetIpInterfaceEntry(MibIPInterfaceRow row);


    // ------------------------- END Interface related -------------------------

    // ------------------------ START UnicastIP related ------------------------

    /**
     * Retrieves the unicast IP address table on the local computer.
     *
     * @param family Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @param table The pointer of the pointer to [info.skyblond.jna.iphlp.MibUnicastIPAddressTable].
     * The pointer must be released by [FreeMibTable].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Invalid family.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_ENOUGH_MEMORY]: Insufficient memory.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: No unicast ip found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     * */
    int GetUnicastIpAddressTable(int family, PointerByReference table);

    /**
     * Frees the buffer allocated by the functions that return tables of network
     * interfaces, addresses, and routes.
     *
     * @param pointer The pointer to free.
     * */
    void FreeMibTable(Pointer pointer);

    /**
     * Initializes a [MibUnicastIPAddressRow] structure with default values for
     * an unicast IP address entry on the local computer.
     *
     * You must initialize the object using this function, before using it.
     * */
    void InitializeUnicastIpAddressEntry(MibUnicastIPAddressRow row);

    /**
     * Retrieves information for an existing unicast IP address entry on the local computer.
     *
     * @param row A pointer to a [MibUnicastIPAddressRow] structure entry for an
     * unicast IP address entry. The [MibUnicastIPAddressRow.InterfaceLuid] or [MibUnicastIPAddressRow.InterfaceIndex]
     * must be initialized, and the [MibUnicastIPAddressRow.Address] must be a
     * valid IPv4 or IPv6 Address. On successful return, this structure will be
     * updated with the properties for an existing unicast IP address.
     *
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND]: Invalid luid or index.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Missing luid and index, or invalid address.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not match the ip.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     * */
    int GetUnicastIpAddressEntry(MibUnicastIPAddressRow row);

    /**
     * Adds a new unicast IP address entry on the local computer.
     *
     * @param row A pointer to [MibUnicastIPAddressRow].
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-createunicastipaddressentry#return-value
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + [com.sun.jna.platform.win32.WinError.ERROR_OBJECT_ALREADY_EXISTS]: The address already exists.
     * + other
     * */
    int CreateUnicastIpAddressEntry(MibUnicastIPAddressRow row);

    /**
     * Sets/updates the properties of an existing unicast IP address entry on the local computer.
     *
     * @param row A pointer to [MibUnicastIPAddressRow].
     *
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setunicastipaddressentry#return-value
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     * */
    int SetUnicastIpAddressEntry(MibUnicastIPAddressRow row);

    /**
     * Deletes an existing unicast IP address entry on the local computer.
     *
     * @param row A pointer to [MibUnicastIPAddressRow].
     *
     * @return If the function succeeds, the return value is [com.sun.jna.platform.win32.WinError.NO_ERROR].
     * If the function fails, the return value is one of the following error codes:
     * + [com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED]: Need run as admin.
     * + [com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER]: Wrong parameter,
     * see https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-deleteunicastipaddressentry#return-value
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_FOUND]: Adapter (luid or index) not found.
     * + [com.sun.jna.platform.win32.WinError.ERROR_NOT_SUPPORTED]: Missing IP stack.
     * + other
     * */
    int DeleteUnicastIpAddressEntry(MibUnicastIPAddressRow row);

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

    int GetAdaptersAddresses(int family, int flags, Pointer reserved, Pointer adapterAddresses, IntByReference sizeOfPointer);

    @Structure.FieldOrder({
            "Length",
            "IfIndex",
            "Next",
            "AdapterName",
            "FirstUnicastAddress",
            "FirstAnycastAddress",
            "FirstMulticastAddress",
            "FirstDnsServerAddress",
            "DnsSuffix",
            "Description",
            "FriendlyName",
            "PhysicalAddress",
            "PhysicalAddressLength",
            "Flags",
            "Mtu",
            "IfType",
            "OperStatus",
            "Ipv6IfIndex",
            "ZoneIndices",
            "FirstPrefix",
            "TransmitLinkSpeed",
            "ReceiveLinkSpeed",
            "FirstWinsServerAddress",
            "FirstGatewayAddress",
            "Ipv4Metric",
            "Ipv6Metric",
            "Luid",
            "Dhcpv4Server",
            "CompartmentId",
            "NetworkGuid",
            "ConnectionType",
            "TunnelType",
            "Dhcpv6Server",
            "Dhcpv6ClientDuid",
            "Dhcpv6ClientDuidLength",
            "Dhcpv6Iaid",
            "FirstDnsSuffix",
    })
    class IP_ADAPTER_ADDRESSES_LH extends Structure {
        public static class ByReference extends IP_ADAPTER_ADDRESSES_LH
                implements Structure.ByReference {}

        public IP_ADAPTER_ADDRESSES_LH(Pointer p) {
            super(p);
            read();
        }

        public IP_ADAPTER_ADDRESSES_LH() {}

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
        public Pointer FirstGatewayAddress;
        public int Ipv4Metric;
        public int Ipv6Metric;
        public WinNT.LUID Luid;
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

    @Structure.FieldOrder({
            "Length",
            "IfIndex",
            "Next",
            "Address",
            "PrefixOrigin",
            "SuffixOrigin",
            "DadState",
            "ValidLifetime",
            "PreferredLifetime",
            "LeaseLifetime",
            "OnLinkPrefixLength"
    })
    class IP_ADAPTER_UNICAST_ADDRESS_LH extends Structure {
        public static class ByReference extends IP_ADAPTER_UNICAST_ADDRESS_LH
                implements Structure.ByReference {}

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
    class IP_ADAPTER_DNS_SERVER_ADDRESS_XP extends Structure {
        public static class ByReference extends IP_ADAPTER_DNS_SERVER_ADDRESS_XP
                implements Structure.ByReference {}

        public int Length;
        public int Reserved;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_ANYCAST_ADDRESS_XP extends Structure {
        public static class ByReference extends IP_ADAPTER_ANYCAST_ADDRESS_XP
                implements Structure.ByReference {}

        public int Length;
        public int Reserved;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_MULTICAST_ADDRESS_XP extends Structure {
        public static class ByReference extends IP_ADAPTER_MULTICAST_ADDRESS_XP
                implements Structure.ByReference {}

        public int Length;
        public int Reserved;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Next", "_String"})
    class IP_ADAPTER_DNS_SUFFIX extends Structure {
        public static class ByReference extends IP_ADAPTER_DNS_SUFFIX
                implements Structure.ByReference {}

        public IP_ADAPTER_DNS_SUFFIX.ByReference Next;
        public char[] _String = new char[256];
    }

    @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr_in extends Structure {
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

    @Structure.FieldOrder({"lpSockaddr", "iSockaddrLength"})
    class SOCKET_ADDRESS extends Structure {
        public Pointer lpSockaddr;
        public int iSockaddrLength;

        InetAddress toAddress() throws UnknownHostException {
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
}
