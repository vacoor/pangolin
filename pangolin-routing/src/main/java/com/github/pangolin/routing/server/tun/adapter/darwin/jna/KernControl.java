package com.github.pangolin.routing.server.tun.adapter.darwin.jna;

import com.sun.jna.NativeLong;
import com.sun.jna.Structure;

/**
 * sys/kern_control.h
 */
public interface KernControl {

    /**
     * The CTLIOCGINFO ioctl can be used to convert a kernel control name to a kernel control id.
     */
    NativeLong CTLIOCGINFO = new NativeLong(0xc0644e03L);

    /**
     * Kernel control names must be no longer than.
     */
    int MAX_KCTL_NAME = 96;

    @SuppressWarnings({"java:S116", "java:S1104", "java:S2160"})
    @Structure.FieldOrder({"ctl_id", "ctl_name"})
    class ctl_info extends Structure {
        public int ctl_id;
        public byte[] ctl_name = new byte[MAX_KCTL_NAME];

        public ctl_info(final String name) {
            Utils.writeToBytes(name, ctl_name);
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
     */
    @SuppressWarnings({"java:S109", "java:S116", "java:S1104", "java:S2160"})
    @Structure.FieldOrder({"sc_len", "sc_family", "ss_sysaddr", /*"sc_id", "sc_unit",*/ "sc_reserved"})
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
//        public int sc_id;

        /**
         * Kernel controller private unit number.
         */
//        public int sc_unit;

        /**
         * Reserved, must be set to zero.
         */
        public int[] sc_reserved = new int[7];

        public sockaddr_ctl(final byte scFamily, final short sysaddr, final int... reserved) {
//            sc_len = (byte) size();
            sc_family = scFamily;
            ss_sysaddr = sysaddr;
            System.arraycopy(reserved, 0, sc_reserved, 0, reserved.length);
        }
    }
}