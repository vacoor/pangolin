package com.github.pangolin.util;

public class Constants {

    public static final byte IPv4_ADDR_SIZE = 4;
    public static final byte IPv6_ADDR_SIZE = 16;

    public static final byte VER_1 = 0x01;

    public static final byte CMD_CONNECT = 0x01;
    public static final byte CMD_SERVICE = (byte) 0xFF;

    public static final byte RSV = 0;

    public static final byte ATYPE_IPv4 = 0x01;
    public static final byte ATYPE_DOMAIN = 0x03;
    public static final byte ATYPE_IPv6 = 0x04;

    public static final byte REPLY_SUCCESS = 0x00;
    public static final byte REPLY_FAILURE = 0x01;
    public static final byte REPLY_FORBIDDEN = 0x02;
    public static final byte REPLY_NETWORK_UNREACHABLE = 0x03;
    public static final byte REPLY_HOST_UNREACHABLE = 0x04;
    public static final byte REPLY_CONNECTION_REFUSED = 0x05;
    public static final byte REPLY_TTL_EXPIRED = 0x06;
    public static final byte REPLY_COMMAND_UNSUPPORTED = 0x07;
    public static final byte REPLY_ADDRESS_UNSUPPORTED = 0x08;

}
