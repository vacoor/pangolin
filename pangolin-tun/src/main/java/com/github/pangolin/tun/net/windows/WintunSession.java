package com.github.pangolin.tun.net.windows;

import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;

import static com.github.pangolin.tun.net.windows.WintunLib.*;
import static org.drasyl.channel.tun.jna.windows.WinBase.INFINITE;
import static org.drasyl.channel.tun.jna.windows.WinError.ERROR_NO_MORE_ITEMS;

public class WintunSession {
    private final WintunAdapter adapter;
    private final WINTUN_SESSION_HANDLE session;

    public WintunSession(final WintunAdapter adapter, final WINTUN_SESSION_HANDLE session) {
        this.adapter = adapter;
        this.session = session;
    }

    public byte[] readPacket() throws IOException {
//        if (closed) {
//            throw new IOException("Device is closed.");
//        }

        while (true) {
            try {
                // read bytes
//                 final Pointer packetSizePointer = new Memory(Native.POINTER_SIZE);
                final IntByReference packetSizePointer = new IntByReference();
                final Pointer packetPointer = WintunReceivePacket(session, packetSizePointer);

                // extract ip version
                final int ipVersion = packetPointer.getByte(0) >> 4;

                // shrink bytebuf to actual required size
//                final int PacketSize = packetSizePointer.getInt(0);
                final int PacketSize = packetSizePointer.getValue();
                byte[] packet = packetPointer.getByteArray(0, PacketSize);
//                final ByteBuf byteBuf = alloc.buffer(PacketSize);
//                byteBuf.writeBytes(packetPointer.getByteArray(0, PacketSize));
                WintunReleaseReceivePacket(session, packetPointer);

                /*
                if (ipVersion == 4) {
                    return new Tun4Packet(byteBuf);
                } else {
                    return new Tun6Packet(byteBuf);
                }
                */
                return packet;
            } catch (final LastErrorException e) {
                if (e.getErrorCode() == ERROR_NO_MORE_ITEMS) {
                    Kernel32.INSTANCE.WaitForSingleObject(WintunGetReadWaitEvent(session), INFINITE);
                } else {
                    throw e;
                }
            }
        }
    }

    //    @Override
    public void writePacket(final byte[] packet, int offset, int len) throws IOException {
//        if (closed) {
//            throw new IOException("Device is closed.");
//        }

        final WinDef.DWORD packetSize = new WinDef.DWORD(len);
        final Pointer packetPointer = WintunAllocateSendPacket(session, packetSize);
        packetPointer.write(0, packet, offset, len);

        WintunSendPacket(session, packetPointer);
    }
}