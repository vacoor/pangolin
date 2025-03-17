package com.github.pangolin.routing.server.tun.adapter.linux.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/netlink.h">netlink.h</a>
 */
public interface Netlink {

    /**
     * Routing/device hook.
     */
    int NETLINK_ROUTE = 0;

    /**
     * It is request message.
     */
    int NLM_F_REQUEST = 0x01;
    /**
     * Multipart message, terminated by NLMSG_DONE.
     */
    int NLM_F_MULTI = 0x02;
    /**
     * Reply with ack, with zero or error code.
     */
    int NLM_F_ACK = 0x04;
    /**
     * Receive resulting notifications.
     */
    int NLM_F_ECHO = 0x08;
    /**
     * Dump was inconsistent due to sequence change.
     */
    int NLM_F_DUMP_INTR = 0x10;
    /**
     * Dump was filtered as requested.
     */
    int NLM_F_DUMP_FILTERED = 0x20;

    /*- Modifiers to GET request */

    /**
     * specify tree root.
     */
    int NLM_F_ROOT = 0x100;
    /**
     * return all matching.
     */
    int NLM_F_MATCH = 0x200;
    /**
     * atomic GET.
     */
    int NLM_F_ATOMIC = 0x400;
    int NLM_F_DUMP = (NLM_F_ROOT | NLM_F_MATCH);

    /*- Modifiers to NEW request */

    /**
     * Override existing.
     */
    int NLM_F_REPLACE = 0x100;
    /**
     * Do not touch, if it exists.
     */
    int NLM_F_EXCL = 0x200;
    /**
     * Create, if it does not exist.
     */
    int NLM_F_CREATE = 0x400;
    /**
     * Add to end of list.
     */
    int NLM_F_APPEND = 0x800;

    /**
     * .
     */
    int NLMSG_ALIGNTO = 4;

    /**
     * Nothing.
     */
    int NLMSG_NOOP = 0x1;
    /**
     * Error.
     */
    int NLMSG_ERROR = 0x2;
    /**
     * End of a dump.
     */
    int NLMSG_DONE = 0x3;
    /**
     * Data lost.
     */
    int NLMSG_OVERRUN = 0x4;
    /**
     * < 0x10: reserved control messages.
     */
    int NLMSG_MIN_TYPE = 0x10;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/netlink.h#L37">sockaddr_nl</a>
     */
    class sockaddr_nl extends Structure {
        /**
         * AF_NETLINK.
         */
        public short nl_family;
        /**
         * zero.
         */
        public short nl_pad;
        /**
         * port ID.
         */
        public int nl_pid;
        /**
         * multicast groups mask.
         */
        public int nl_groups;

        /**
         * {@inheritDoc}
         */
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("nl_family", "nl_pad", "nl_pid", "nl_groups");
        }
    }

    /**
     * struct nlmsghdr - fixed format metadata header of Netlink messages.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/uapi/linux/netlink.h#L52">nlmsghdr</a>
     */
    class nlmsghdr extends Structure {
        /**
         * Length of message including header.
         */
        public int nlmsg_len;
        /**
         * Message content type.
         */
        public short nlmsg_type;
        /**
         * Additional flags.
         */
        public short nlmsg_flags;
        /**
         * Sequence number.
         */
        public int nlmsg_seq;
        /**
         * Sending process port ID.
         */
        public int nlmsg_pid;

        public nlmsghdr(final Pointer p) {
            super(p);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "nlmsg_len", "nlmsg_type",
                    "nlmsg_flags", "nlmsg_seq", "nlmsg_pid"
            );
        }
    }

}
