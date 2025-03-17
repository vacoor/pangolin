package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.github.pangolin.routing.server.tun.adapter.unix.Utils;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * This header defines an API to communicate between a kernel
 * extension and a process outside of the kernel.
 *
 * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/kern_control.h">sys/kern_control.h</a>
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public interface KernControl {

    /**
     * The CTLIOCGINFO ioctl can be used to convert a kernel control name to a kernel control id.
     */
    NativeLong CTLIOCGINFO = new NativeLong(0xc0644e03L);

    /**
     * Kernel control names must be no longer than.
     */
    int MAX_KCTL_NAME = 96;

    /**
     * This structure is used with the CTLIOCGINFO ioctl to
     * translate from a kernel control name to a control id.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/kern_control.h">sys/kern_control.h</a>
     */
    @SuppressWarnings({"java:S116", "java:S1104", "java:S2160"})
    class ctl_info extends Structure {
        /**
         * The kernel control id, filled out upon return.
         */
        public int ctl_id;

        /**
         * The kernel control name to find.
         */
        public byte[] ctl_name = new byte[MAX_KCTL_NAME];

        public ctl_info(final String name) {
            Utils.writeToBytes(name, ctl_name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("ctl_id", "ctl_name");
        }
    }

    /**
     * The controller address structure is used to establish
     * contact between a user client and a kernel controller. The
     * sc_id/sc_unit uniquely identify each controller. sc_id is a
     * unique identifier assigned to the controller. The identifier can
     * be assigned by the system at registration time or be a 32-bit
     * creator code obtained from Apple Computer. sc_unit is a unit
     * number for this sc_id, and is privately used by the kernel
     * controller to identify several instances of the controller.
     *
     * @see <a href="https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/kern_control.h">sys/kern_control.h</a>
     */
    @SuppressWarnings({"java:S109", "java:S116", "java:S1104", "java:S2160"})
    class sockaddr_ctl extends Structure {
        /**
         * The length of the structure.
         */
        public byte sc_len = 32;

        /**
         * AF_SYSTEM.
         */
        public byte sc_family;

        /**
         * AF_SYS_KERNCONTROL.
         */
        public short ss_sysaddr;

        /**
         * Controller unique identifier.
         */
        public int sc_id;

        /**
         * Kernel controller private unit number.
         */
        public int sc_unit;

        /**
         * Reserved, must be set to zero.
         */
        public int[] sc_reserved = new int[5];

        public sockaddr_ctl(final byte family, final short sysaddr,
                            final int scId, final int scUnit, final int... reserved) {
            sc_len = (byte) size();
            sc_family = family;
            ss_sysaddr = sysaddr;
            sc_id = scId;
            sc_unit = scUnit;
            System.arraycopy(reserved, 0, sc_reserved, 0, reserved.length);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "sc_len", "sc_family", "ss_sysaddr",
                    "sc_id", "sc_unit", "sc_reserved"
            );
        }
    }
}