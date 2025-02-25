package com.github.pangolin.routing.server.tun.adapter.linux.jna;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;

public final class LibC {

    /*
    static {
        // WARN: Failed to allocate closure on Armbian
        Native.register(LibC.class, Platform.C_LIBRARY_NAME);
    }
    */

    private LibC() {
        // JNA mapping.
    }

    /*
    public static native int open(final String path, final int flags) throws LastErrorException;

    public static native int close(final int fd) throws LastErrorException;

    public static native int read(final int fd, final byte[] buf, final long nBytes) throws LastErrorException;

    public static native int read(final int fd, final ByteBuffer buf, final long nBytes) throws LastErrorException;

    public static native int write(final int fd, final byte[] buf, final int nBytes) throws LastErrorException;

    public static native int write(final int fd, final ByteBuffer buf, final int nBytes) throws LastErrorException;

    public static native int socket(final int domain, final int type, final int protocol) throws LastErrorException;

    public static native int connect(final int socket, final Structure address, final int addressLen) throws LastErrorException;

    @SuppressWarnings("java:S117")
    public static native int getsockopt(final int socket, final int level, final int optionName, final Structure optionValue, final IntByReference optionLen) throws LastErrorException;

    public static native int ioctl(final int fd, final NativeLong request, final Structure argp) throws LastErrorException;

    public static native int getifaddrs(final Structure ifap);

    public static native void freeifaddrs(final Structure ifa);
    */

    public static int open(final String path, final int flags) throws LastErrorException {
        return API.INSTANCE.open(path, flags);
    }

    public static int close(final int fd) throws LastErrorException {
        return API.INSTANCE.close(fd);
    }

    public static int read(final int fd, final byte[] buf, final long nBytes) throws LastErrorException {
        return API.INSTANCE.read(fd, buf, nBytes);
    }

    public static int read(final int fd, final ByteBuffer buf, final long nBytes) throws LastErrorException {
        return API.INSTANCE.read(fd, buf, nBytes);
    }

    public static int write(final int fd, final byte[] buf, final int nBytes) throws LastErrorException {
        return API.INSTANCE.write(fd, buf, nBytes);
    }

    public static int write(final int fd, final ByteBuffer buf, final int nBytes) throws LastErrorException {
        return API.INSTANCE.write(fd, buf, nBytes);
    }

    public static int write(final int fd, final Pointer buf, final int nBytes) throws LastErrorException {
        return API.INSTANCE.write(fd, buf, nBytes);
    }


    public static int socket(final int domain, final int type, final int protocol) throws LastErrorException {
        return API.INSTANCE.socket(domain, type, protocol);
    }

    public static int connect(final int socket, final Structure address, final int addressLen) throws LastErrorException {
        return API.INSTANCE.connect(socket, address, addressLen);
    }

    @SuppressWarnings("java:S117")
    public static int getsockopt(final int socket, final int level, final int optionName, final Structure optionValue, final IntByReference optionLen) throws LastErrorException {
        return API.INSTANCE.getsockopt(socket, level, optionName, optionValue, optionLen);
    }

    public static int ioctl(final int fd, final NativeLong request, final Structure argp) throws LastErrorException {
        return API.INSTANCE.ioctl(fd, request, argp);
    }

    public static int getifaddrs(final Structure ifap) {
        return API.INSTANCE.getifaddrs(ifap);
    }

    public static void freeifaddrs(final Structure ifa) {
        API.INSTANCE.freeifaddrs(ifa);
    }

    public interface API extends com.sun.jna.platform.linux.LibC {

        API INSTANCE = Native.load(Platform.C_LIBRARY_NAME, API.class);

        int open(final String path, final int flags) throws LastErrorException;

        int close(final int fd) throws LastErrorException;

        int read(final int fd, final byte[] buf, final long nBytes) throws LastErrorException;

        int read(final int fd, final ByteBuffer buf, final long nBytes) throws LastErrorException;

        int write(final int fd, final byte[] buf, final int nBytes) throws LastErrorException;

        int write(final int fd, final ByteBuffer buf, final int nBytes) throws LastErrorException;

        int write(final int fd, final Pointer buf, final int nBytes) throws LastErrorException;

        int socket(final int domain, final int type, final int protocol) throws LastErrorException;

        int connect(final int socket, final Structure address, final int addressLen) throws LastErrorException;

        @SuppressWarnings("java:S117")
        int getsockopt(final int socket, final int level, final int optionName, final Structure optionValue, final IntByReference optionLen) throws LastErrorException;

        int ioctl(final int fd, final NativeLong request, final Structure argp) throws LastErrorException;

        int getifaddrs(final Structure ifap);

        void freeifaddrs(final Structure ifa);

    }

}
