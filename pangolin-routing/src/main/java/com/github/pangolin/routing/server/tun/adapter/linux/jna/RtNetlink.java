package com.github.pangolin.routing.server.tun.adapter.linux.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/rtnetlink.h">rtnetlink.h</a>
 */
public interface RtNetlink {
    int RTM_NEWADDR = 20;
    int RTM_DELADDR = 21;
    int RTM_GETADDR = 22;

    int RTM_NEWROUTE = 24;
    int RTM_DELROUTE = 25;
    int RTM_GETROUTE = 26;

    int RT_SCOPE_UNIVERSE = 0;

    /*- User defined values  */

    int RT_SCOPE_SITE = 200;
    int RT_SCOPE_LINK = 253;
    int RT_SCOPE_HOST = 254;
    int RT_SCOPE_NOWHERE = 255;

    /*- rtm_type */

    int RTN_UNICAST = 1;

    /*- rtm_protocol */

    int RTPROT_UNSPEC = 0;
    /**
     * Route installed by ICMP redirects; not used by current IPv4.
     */
    int RTPROT_REDIRECT = 1;
    /**
     * Route installed by kernel.
     */
    int RTPROT_KERNEL = 2;
    /**
     * Route installed during boot.
     */
    int RTPROT_BOOT = 3;
    /**
     * Route installed by administrator.
     */
    int RTPROT_STATIC = 4;


    int RT_TABLE_UNSPEC = 0;

    /*- User defined values */

    int RT_TABLE_COMPAT = 252;
    int RT_TABLE_DEFAULT = 253;
    int RT_TABLE_MAIN = 254;
    int RT_TABLE_LOCAL = 255;
    int RT_TABLE_MAX = 0xFFFFFFFF;

    /*- Routing message attributes. */

    int RTA_DST = 1;
    int RTA_OIF = 4;
    int RTA_GATEWAY = 5;
    int RTA_PRIORITY = 6;
    int RTA_METRICS = 8;
    int RTA_TABLE = 15;

    int RTA_MAX = 31;

    /*- RTnetlink multicast groups - backwards compatibility for userspace. */

    int RTMGRP_LINK = 1;
    int RTMGRP_NOTIFY = 2;
    int RTMGRP_NEIGH = 4;
    int RTMGRP_TC = 8;

    int RTMGRP_IPV4_IFADDR = 0x10;
    int RTMGRP_IPV4_MROUTE = 0x20;
    int RTMGRP_IPV4_ROUTE = 0x40;
    int RTMGRP_IPV4_RULE = 0x80;

    int RTMGRP_IPV6_IFADDR = 0x100;
    int RTMGRP_IPV6_MROUTE = 0x200;
    int RTMGRP_IPV6_ROUTE = 0x400;
    int RTMGRP_IPV6_IFINFO = 0x800;

    int RTMGRP_DECnet_IFADDR = 0x1000;
    int RTMGRP_DECnet_ROUTE = 0x4000;

    int RTMGRP_IPV6_PREFIX = 0x20000;

    int RTA_ALIGNTO = 4;

    /**
     * Generic structure for encapsulation of optional route information.
     * It is reminiscent of sockaddr, but with sa_family replaced
     * with attribute type.
     */
    class rtattr extends Structure {
        public short rta_len;
        public short rta_type;

        public rtattr(final Pointer p) {
            super(p);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("rta_len", "rta_type");
        }
    }

    class rtmsg extends Structure {
        public byte rtm_family;
        public byte rtm_dst_len;
        public byte rtm_src_len;
        public byte rtm_tos;

        /**
         * Routing table id.
         */
        public byte rtm_table;
        /**
         * Routing protocol.
         */
        public byte rtm_protocol;
        public byte rtm_scope;
        public byte rtm_type;

        public int rtm_flags;


        public rtmsg(final Pointer p) {
            super(p);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "rtm_family", "rtm_dst_len", "rtm_src_len",
                    "rtm_tos", "rtm_table", "rtm_protocol",
                    "rtm_scope", "rtm_type", "rtm_flags"
            );
        }
    }
}
