package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util;

import io.netty.buffer.ByteBuf;

/**
 * TCP Options raw-byte codec — parse incoming options and build outgoing options
 * without pcap4j objects.
 *
 * <p>TCP option wire format:
 * <pre>
 *   Kind=0 (EOL):  1 byte
 *   Kind=1 (NOP):  1 byte
 *   Kind=N, Len=L: [kind(1)] [len(1)] [data(len-2)]
 * </pre>
 */
public final class TcpOptionCodec {

    public static final byte OPT_EOL            = 0;
    public static final byte OPT_NOP            = 1;
    public static final byte OPT_MSS            = 2;
    public static final byte OPT_WSCALE         = 3;
    public static final byte OPT_SACK_PERMITTED = 4;
    public static final byte OPT_SACK           = 5;
    public static final byte OPT_TIMESTAMP      = 8;

    private TcpOptionCodec() {
    }

    // ---- parse ----

    /**
     * Extract MSS value (Kind=2). Returns -1 if not present.
     */
    public static int parseMss(ByteBuf opts) {
        int i = opts.readerIndex();
        int end = i + opts.readableBytes();
        while (i < end) {
            byte kind = opts.getByte(i);
            if (kind == OPT_EOL) break;
            if (kind == OPT_NOP) { i++; continue; }
            if (i + 1 >= end) break;
            int len = opts.getUnsignedByte(i + 1);
            if (len < 2 || i + len > end) break;
            if (kind == OPT_MSS && len == 4) {
                return opts.getUnsignedShort(i + 2);
            }
            i += len;
        }
        return -1;
    }

    /**
     * Extract window scale factor (Kind=3). Returns -1 if not present.
     */
    public static int parseWindowScale(ByteBuf opts) {
        int i = opts.readerIndex();
        int end = i + opts.readableBytes();
        while (i < end) {
            byte kind = opts.getByte(i);
            if (kind == OPT_EOL) break;
            if (kind == OPT_NOP) { i++; continue; }
            if (i + 1 >= end) break;
            int len = opts.getUnsignedByte(i + 1);
            if (len < 2 || i + len > end) break;
            if (kind == OPT_WSCALE && len == 3) {
                return opts.getUnsignedByte(i + 2);
            }
            i += len;
        }
        return -1;
    }

    /**
     * Check whether SACK Permitted option (Kind=4) is present.
     */
    public static boolean hasSackPermitted(ByteBuf opts) {
        int i = opts.readerIndex();
        int end = i + opts.readableBytes();
        while (i < end) {
            byte kind = opts.getByte(i);
            if (kind == OPT_EOL) break;
            if (kind == OPT_NOP) { i++; continue; }
            if (i + 1 >= end) break;
            int len = opts.getUnsignedByte(i + 1);
            if (len < 2 || i + len > end) break;
            if (kind == OPT_SACK_PERMITTED) {
                return true;
            }
            i += len;
        }
        return false;
    }

    /**
     * 解析 SACK 选项(Kind=5)块序列。每块 8 字节(left_edge + right_edge),
     * 返回扁平整数数组 {@code [start1, end1, start2, end2, ...]};选项缺失或无块时返回
     * {@code null}。对齐 Linux {@code tcp_parse_sack} 的写法。
     */
    public static int[] parseSackBlocks(ByteBuf opts) {
        int i = opts.readerIndex();
        int end = i + opts.readableBytes();
        while (i < end) {
            byte kind = opts.getByte(i);
            if (kind == OPT_EOL) break;
            if (kind == OPT_NOP) { i++; continue; }
            if (i + 1 >= end) break;
            int len = opts.getUnsignedByte(i + 1);
            if (len < 2 || i + len > end) break;
            if (kind == OPT_SACK) {
                int blockBytes = len - 2;
                if (blockBytes <= 0 || blockBytes % 8 != 0) {
                    return null;
                }
                int pairs = blockBytes / 8;
                int[] out = new int[pairs * 2];
                int off = i + 2;
                for (int p = 0; p < pairs; p++) {
                    out[2 * p]     = opts.getInt(off);
                    out[2 * p + 1] = opts.getInt(off + 4);
                    off += 8;
                }
                return out;
            }
            i += len;
        }
        return null;
    }

    /**
     * Extract timestamp option (Kind=8). Returns [tsval, tsecr] or null if not present.
     */
    public static long[] parseTimestamp(ByteBuf opts) {
        int i = opts.readerIndex();
        int end = i + opts.readableBytes();
        while (i < end) {
            byte kind = opts.getByte(i);
            if (kind == OPT_EOL) break;
            if (kind == OPT_NOP) { i++; continue; }
            if (i + 1 >= end) break;
            int len = opts.getUnsignedByte(i + 1);
            if (len < 2 || i + len > end) break;
            if (kind == OPT_TIMESTAMP && len == 10) {
                long tsval = opts.getUnsignedInt(i + 2);
                long tsecr = opts.getUnsignedInt(i + 6);
                return new long[]{tsval, tsecr};
            }
            i += len;
        }
        return null;
    }

    // ---- build ----

    /**
     * Write SYN/SYN-ACK options into {@code out}:
     * MSS(4B) + NOP(1B) + Window Scale(3B) + NOP(1B) + NOP(1B) + SACK-Permitted(2B)
     * = 12 bytes total (4-byte aligned).
     *
     * @param mss    MSS value to advertise
     * @param wscale window scale shift count (0–14), or -1 to omit Window Scale option
     */
    public static void writeSynOptions(ByteBuf out, int mss, int wscale) {
        // MSS: kind(1) + len(1) + value(2) = 4 bytes
        out.writeByte(OPT_MSS);
        out.writeByte(4);
        out.writeShort(mss);

        if (wscale >= 0) {
            // NOP + Window Scale: 1 + 3 = 4 bytes
            out.writeByte(OPT_NOP);
            out.writeByte(OPT_WSCALE);
            out.writeByte(3);
            out.writeByte(wscale & 0xFF);
        } else {
            // Pad with 4 NOPs to maintain alignment
            out.writeByte(OPT_NOP);
            out.writeByte(OPT_NOP);
            out.writeByte(OPT_NOP);
            out.writeByte(OPT_NOP);
        }

        // NOP + NOP + SACK-Permitted: 1 + 1 + 2 = 4 bytes
        out.writeByte(OPT_NOP);
        out.writeByte(OPT_NOP);
        out.writeByte(OPT_SACK_PERMITTED);
        out.writeByte(2);
    }

    public static void writeSynOptions(ByteBuf out, int mss, int wscale, Long tsval, Long tsecr) {
        writeSynOptions(out, mss, wscale);
        if (tsval != null && tsecr != null) {
            writeTimestampOption(out, tsval, tsecr);
        }
    }

    /**
     * Write timestamp option into {@code out}:
     * NOP(1B) + NOP(1B) + Timestamp kind(1B) + len(1B) + tsval(4B) + tsecr(4B) = 12 bytes.
     */
    public static void writeTimestampOption(ByteBuf out, long tsval, long tsecr) {
        out.writeByte(OPT_NOP);
        out.writeByte(OPT_NOP);
        out.writeByte(OPT_TIMESTAMP);
        out.writeByte(10);
        out.writeInt((int) tsval);
        out.writeInt((int) tsecr);
    }
}
