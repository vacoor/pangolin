package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import io.netty.buffer.ByteBuf;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.List;

public class TcpBuffer {
    private TcpPort srcPort;
    private TcpPort dstPort;
    private int sequenceNumber;
    private int acknowledgmentNumber;
    private byte dataOffset;
    private byte reserved;
    private boolean urg;
    private boolean ack;
    private boolean psh;
    private boolean rst;
    private boolean syn;
    private boolean fin;
    private short window;
    private short checksum;
    private short urgentPointer;
    private List<TcpPacket.TcpOption> options;
    private byte[] padding;
    private Packet.Builder payloadBuilder;
    /**
     * Zero-copy path: upstream data held as a retained ByteBuf slice.
     * When non-null, {@link #payloadBuilder} is ignored on the transmit path.
     * Must be released via {@link #release()} when the segment is discarded
     * (ACKed or connection destroyed).
     */
    private ByteBuf rawPayload;
    private InetAddress srcAddr;
    private InetAddress dstAddr;


    public long tstamp;
    public transient int sacked;

    public long skb_mstamp_ns;

    private transient int cachedPayloadLength = -1;

    /**
     * 返回 payload 长度，结果会被缓存，避免反复触发 pcap4j 序列化。
     * rawPayload 路径直接返回 readableBytes，无需序列化。
     */
    public int payloadLength() {
        if (rawPayload != null) {
            return rawPayload.readableBytes();
        }
        if (cachedPayloadLength < 0) {
            Packet.Builder p = payloadBuilder;
            cachedPayloadLength = (p != null) ? p.build().length() : 0;
        }
        return cachedPayloadLength;
    }

    public ByteBuf rawPayload() {
        return rawPayload;
    }

    /**
     * 设置零拷贝 payload（retained ByteBuf slice）。
     * 调用方已负责 retain，本对象持有所有权，最终由 {@link #release()} 归还。
     */
    public TcpBuffer rawPayload(ByteBuf rawPayload) {
        this.rawPayload = rawPayload;
        this.cachedPayloadLength = -1;
        return this;
    }

    /**
     * 释放 rawPayload 引用计数。在 segment 被 ACK 确认或连接关闭时必须调用。
     * 对没有 rawPayload 的 TcpBuffer 调用为无操作。
     */
    public void release() {
        if (rawPayload != null) {
            rawPayload.release();
            rawPayload = null;
        }
    }

    public TcpPort srcPort() {
        return srcPort;
    }

    public TcpBuffer srcPort(TcpPort srcPort) {
        this.srcPort = srcPort;
        return this;
    }

    public TcpPort dstPort() {
        return dstPort;
    }

    public TcpBuffer dstPort(TcpPort dstPort) {
        this.dstPort = dstPort;
        return this;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    public TcpBuffer sequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public int acknowledgmentNumber() {
        return acknowledgmentNumber;
    }

    public TcpBuffer acknowledgmentNumber(int acknowledgmentNumber) {
        this.acknowledgmentNumber = acknowledgmentNumber;
        return this;
    }

    public byte dataOffset() {
        return dataOffset;
    }

    public void dataOffset(byte dataOffset) {
        this.dataOffset = dataOffset;
    }

    public byte reserved() {
        return reserved;
    }

    public TcpBuffer reserved(byte reserved) {
        this.reserved = reserved;
        return this;
    }

    public boolean urg() {
        return urg;
    }

    public TcpBuffer urg(boolean urg) {
        this.urg = urg;
        return this;
    }

    public boolean ack() {
        return ack;
    }

    public TcpBuffer ack(boolean ack) {
        this.ack = ack;
        return this;
    }

    public boolean psh() {
        return psh;
    }

    public TcpBuffer psh(boolean psh) {
        this.psh = psh;
        return this;
    }

    public boolean rst() {
        return rst;
    }

    public TcpBuffer rst(boolean rst) {
        this.rst = rst;
        return this;
    }

    public boolean syn() {
        return syn;
    }

    public TcpBuffer syn(boolean syn) {
        this.syn = syn;
        return this;
    }

    public boolean fin() {
        return fin;
    }

    public TcpBuffer fin(boolean fin) {
        this.fin = fin;
        return this;
    }

    public short window() {
        return window;
    }

    public TcpBuffer window(short window) {
        this.window = window;
        return this;
    }

    public short checksum() {
        return checksum;
    }

    public TcpBuffer checksum(short checksum) {
        this.checksum = checksum;
        return this;
    }

    public short urgentPointer() {
        return urgentPointer;
    }

    public TcpBuffer urgentPointer(short urgentPointer) {
        this.urgentPointer = urgentPointer;
        return this;
    }

    public List<TcpPacket.TcpOption> options() {
        return options;
    }

    public TcpBuffer options(List<TcpPacket.TcpOption> options) {
        this.options = options;
        return this;
    }

    public byte[] padding() {
        return padding;
    }

    public TcpBuffer padding(byte[] padding) {
        this.padding = padding;
        return this;
    }

    public Packet.Builder payloadBuilder() {
        return payloadBuilder;
    }

    public TcpBuffer payloadBuilder(Packet.Builder payloadBuilder) {
        this.payloadBuilder = payloadBuilder;
        this.cachedPayloadLength = -1;
        return this;
    }

    public InetAddress srcAddr() {
        return srcAddr;
    }

    public TcpBuffer srcAddr(InetAddress srcAddr) {
        this.srcAddr = srcAddr;
        return this;
    }

    public InetAddress dstAddr() {
        return dstAddr;
    }

    public TcpBuffer dstAddr(InetAddress dstAddr) {
        this.dstAddr = dstAddr;
        return this;
    }

    public TcpPacket.Builder asBuilder() {
        return new TcpPacket.Builder()
                .srcPort(srcPort)
                .dstPort(dstPort)
                .sequenceNumber(sequenceNumber)
                .acknowledgmentNumber(acknowledgmentNumber)
                .dataOffset(dataOffset)
                .reserved(reserved)
                .urg(urg)
                .ack(ack)
                .psh(psh)
                .rst(rst)
                .syn(syn)
                .fin(fin)
                .window(window)
                .checksum(checksum)
                .urgentPointer(urgentPointer)
                .options(options)
                .padding(padding)
                .payloadBuilder(payloadBuilder)
                .srcAddr(srcAddr)
                .dstAddr(dstAddr)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
    }
}
