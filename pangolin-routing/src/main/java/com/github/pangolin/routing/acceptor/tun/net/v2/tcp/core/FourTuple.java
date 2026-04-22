package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable TCP 4-tuple (srcAddr, srcPort, dstAddr, dstPort) identifying a connection.
 * Equals/hashCode are based on all four fields with 32-bit circular-safe hash.
 */
public final class FourTuple {

    private final byte[] srcAddr;
    private final int    srcPort;
    private final byte[] dstAddr;
    private final int    dstPort;
    private final int    hash;

    private FourTuple(byte[] srcAddr, int srcPort, byte[] dstAddr, int dstPort) {
        this.srcAddr = srcAddr;
        this.srcPort = srcPort;
        this.dstAddr = dstAddr;
        this.dstPort = dstPort;
        this.hash    = Objects.hash(Arrays.hashCode(srcAddr), srcPort,
                                    Arrays.hashCode(dstAddr), dstPort);
    }

    /** Build from an incoming TCP packet (srcAddr/srcPort = client; dstAddr/dstPort = local). */
    public static FourTuple of(TcpPacketBuf pkt) {
        return new FourTuple(pkt.srcAddrBytes(), pkt.tcpSrcPort(),
                             pkt.dstAddrBytes(), pkt.tcpDstPort());
    }

    public static FourTuple of(byte[] srcAddr, int srcPort, byte[] dstAddr, int dstPort) {
        return new FourTuple(srcAddr.clone(), srcPort, dstAddr.clone(), dstPort);
    }

    public byte[] srcAddrBytes() { return srcAddr; }
    public int    srcPort()      { return srcPort; }
    public byte[] dstAddrBytes() { return dstAddr; }
    public int    dstPort()      { return dstPort; }

    public InetAddress srcInetAddress() {
        try { return InetAddress.getByAddress(srcAddr); }
        catch (UnknownHostException e) { throw new IllegalStateException(e); }
    }

    public InetAddress dstInetAddress() {
        try { return InetAddress.getByAddress(dstAddr); }
        catch (UnknownHostException e) { throw new IllegalStateException(e); }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FourTuple)) return false;
        FourTuple t = (FourTuple) o;
        return srcPort == t.srcPort && dstPort == t.dstPort
                && Arrays.equals(srcAddr, t.srcAddr)
                && Arrays.equals(dstAddr, t.dstAddr);
    }

    @Override
    public int hashCode() { return hash; }

    @Override
    public String toString() {
        return srcInetAddress().getHostAddress() + ":" + srcPort
             + " => " + dstInetAddress().getHostAddress() + ":" + dstPort;
    }
}
