package com.github.pangolin.routing.server.tun.adapter.unix.jna;

import com.sun.jna.*;
import com.sun.jna.platform.unix.LibCAPI;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;

/**
 * <I>libc</I> API.
 */
public interface LibC extends LibCAPI, Library {

    LibC INSTANTCE = Native.load("c", LibC.class);

    /*-
    static {
        // WARN: Failed to allocate closure on Armbian
        Native.register("c");
    }
    */

    /**
     * Look up the error message string corresponding to an error number.
     *
     * @param errno the error number
     * @return the error message string corresponding to the error number
     */
    String strerror(final int errno);

    /**
     * Opens the file for I/O and returns an associated file descriptor.
     * <p>
     * Omode is one of OREAD, OWRITE, ORDWR, or OEXEC, asking for permission
     * to read, write, read and write, or execute, respectively. In addition,
     * there are three values that can be ORed with the omode: OTRUNC says to
     * truncate the file to zero length before opening it; OCEXEC says to
     * close the file when an exec((3)) or execl system call is made; ORCLOSE
     * says to remove the file when it is closed (by everyone who has a copy
     * of the file descriptor); and OAPPEND says to open the file in append-
     * only mode, so that writes are always appended to the end of the file.
     * Open fails if the file does not exist or the user does not have permis-
     * sion to open it for the requested purpose (see stat((3)) for a descrip-
     * tion of permissions). The user must have write permission on the file
     * if the OTRUNC bit is set. For the open system call (unlike the im-
     * plicit open in exec((3))), OEXEC is actually identical to OREAD.
     *
     * @param file  the file
     * @param omode OREAD, OWRITE, ORDWR, or OEXEC
     * @return an associated file descriptor
     */
    int open(final String file, final int omode);

    /**
     * Closes the file associated with a file descriptor.
     * <p>
     * Provided the file descriptor is a valid open descriptor,
     * close is guaranteed to close it; there will be no error.
     * Files are closed automatically upon termination of a process;
     * close allows the file descriptor to be reused.
     *
     * @param fd a file descriptor
     * @return returns zero on success. On error, -1 is returned,
     * and {@code errno} is set appropriately.
     * {@code close()} should not be retried after an error.
     */
    int close(final int fd);

    /**
     * Reads nbytes bytes of data from the offset in the file associated
     * with fd into memory at buf. The offset is advanced by the number of
     * bytes read.  It is not guaranteed that all nbytes bytes will be read;
     * for example if the file refers to the console, at most one line will be
     * returned. In any event the number of bytes read is returned. A return
     * value of 0 is conventionally interpreted as end of file.
     *
     * @param fd     a file descriptor
     * @param buf    the buf
     * @param nbytes the nbytes
     * @return the number of bytes read is returned.  A return
     * value of 0 is conventionally interpreted as end of file.
     */
    int read(final int fd, final byte[] buf, final long nbytes);

    /**
     * Reads nbytes bytes of data from the offset in the file associated
     * with fd into memory at buf. The offset is advanced by the number of
     * bytes read.  It is not guaranteed that all nbytes bytes will be read;
     * for example if the file refers to the console, at most one line will be
     * returned. In any event the number of bytes read is returned. A return
     * value of 0 is conventionally interpreted as end of file.
     *
     * @param fd     a file descriptor
     * @param buf    the buf
     * @param nbytes the nbytes
     * @return the number of bytes read is returned.  A return
     * value of 0 is conventionally interpreted as end of file.
     */
    int read(final int fd, final ByteBuffer buf, final long nbytes);

    /**
     * Reads nbytes bytes of data from the offset in the file associated
     * with fd into memory at buf. The offset is advanced by the number of
     * bytes read.  It is not guaranteed that all nbytes bytes will be read;
     * for example if the file refers to the console, at most one line will be
     * returned. In any event the number of bytes read is returned. A return
     * value of 0 is conventionally interpreted as end of file.
     *
     * @param fd     a file descriptor
     * @param buf    the buf
     * @param nbytes the nbytes
     * @return the number of bytes read is returned.  A return
     * value of 0 is conventionally interpreted as end of file.
     */
    int read(final int fd, final Pointer buf, final long nbytes);

    /**
     * Writes nbytes bytes of data starting at buf to the file associ-
     * ated with fd at the file offset.  The offset is advanced by the number
     * of  bytes  written.   The  number of characters actually written is re-
     * turned. It should be regarded as an error if this is not the  same  as
     * requested.
     *
     * @param fd     a file descriptor
     * @param buf    the buf
     * @param nbytes the nbytes
     * @return the number of characters actually written
     */
    int write(final int fd, final byte[] buf, final int nbytes);

    /**
     * Writes nbytes bytes of data starting at buf to the file associ-
     * ated with fd at the file offset.  The offset is advanced by the number
     * of  bytes  written.   The  number of characters actually written is re-
     * turned. It should be regarded as an error if this is not the  same  as
     * requested.
     *
     * @param fd     a file descriptor
     * @param buf    the buf
     * @param nbytes the nbytes
     * @return the number of characters actually written
     */
    int write(final int fd, final ByteBuffer buf, final int nbytes);

    /**
     * Writes nbytes bytes of data starting at buf to the file associ-
     * ated with fd at the file offset.  The offset is advanced by the number
     * of  bytes  written.   The  number of characters actually written is re-
     * turned. It should be regarded as an error if this is not the  same  as
     * requested.
     *
     * @param fd     a file descriptor
     * @param buf    the buf
     * @param nbytes the nbytes
     * @return the number of characters actually written
     */
    int write(final int fd, final Pointer buf, final int nbytes);

    /**
     * Creates an endpoint for communication and returns a descriptor.
     *
     * @param domain   the communications domain
     * @param type     the socket has the indicated type
     * @param protocol the protocol to be used with the socket
     * @return a -1 is returned if an error occurs, otherwise the return value is a descriptor referencing the socket.
     */
    int socket(final int domain, final int type, final int protocol);

    /**
     * Initiate a connection on a socket.
     *
     * @param socket     the socket
     * @param address    the socket address
     * @param addressLen the socket address structure length
     * @return upon successful completion, a value of 0 is returned.  Otherwise, a value of -1 is returned
     */
    int connect(final int socket, final Structure address, final int addressLen);

    /**
     * Get options on sockets.
     *
     * @param socket
     * @param level
     * @param option_name
     * @param option_value
     * @param option_len
     * @return
     */
    int getsockopt(final int socket, final int level,
                   final int option_name, final Structure option_value, final IntByReference option_len);

    /**
     * Control device.
     *
     * @param fd      an open file descriptor
     * @param request
     * @param argp
     * @return if an error has occurred, a value of -1 is returned
     */
    int ioctl(final int fd, final NativeLong request, final Structure argp);


    /**
     * Maps the interface name specified in ifname to its corresponding index.
     *
     * @param ifname the interface name
     * @return the index number of the interface. If the interface is not found, a value of 0 is returned
     */
    int if_nametoindex(final String ifname);

    String if_indextoname(final int ifindex, final byte[] ifname);

    /**
     * Get interface addresses.
     * <p>
     * The data returned by getifaddrs() is dynamically allocated
     * and should be freed using freeifaddrs() when no longer needed.
     *
     * @param ifap the interface addresses pointer
     * @return the value 0 if successful; otherwise the value -1 is returned
     */
    int getifaddrs(final Structure ifap);

    void freeifaddrs(final Structure ifp);

    int bind(int sockfd, Structure addr, int addrlen);

    int send(int sockfd, Pointer buf, int len, int flags);

    int recv(int sockfd, Pointer buf, int len, int flags);

    int getpid();

    int sysctl(int[] mib, int nameLen, Pointer oldp, IntByReference oldlenp, Pointer newp, IntByReference newlen);


}
