package com.github.pangolin.routing.tun.wintun.win32.iphlp;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

interface IPHlpAPI extends Library {
  IPHlpAPI INSTANCE = Native.load("IPHlpAPI", IPHlpAPI.class, W32APIOptions.ASCII_OPTIONS);

  int AF_UNSPEC = 0;
  int AF_INET = 2;
  int AF_INET6 = 23;

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

  @Structure.FieldOrder({"LowPart", "HighPart"})
  class LUID extends Structure {
    public int LowPart;
    public int HighPart;
  }

  int GetAdaptersAddresses(
      int family,
      int flags,
      Pointer reserved,
      Pointer adapterAddresses,
      IntByReference sizePointer);
}