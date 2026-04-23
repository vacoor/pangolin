package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpPacketBuilder;
import io.netty.buffer.ByteBuf;

/**
 * 测试用 raw IPv4+TCP 包构造器。薄封装
 * {@link TcpPacketBuilder#buildRaw},只暴露测试关心的参数。
 *
 * <p>所有返回值是 {@code ByteBuf},由 {@link TcpStackHarness#sendInbound} 消费。
 * checksums 由 {@code TcpPacketBuilder} 计算(但 v2 栈的预校验不校验 checksum,
 * 所以字段值对测试行为无影响)。
 */
public final class PacketFactory {

    public static final int TCP_FLAG_FIN = 0x01;
    public static final int TCP_FLAG_SYN = 0x02;
    public static final int TCP_FLAG_RST = 0x04;
    public static final int TCP_FLAG_PSH = 0x08;
    public static final int TCP_FLAG_ACK = 0x10;

    public static final int DEFAULT_WINDOW = 65535;

    private PacketFactory() {}

    // ---- Builder ----

    public static Builder tcp(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort) {
        return new Builder(srcIp, srcPort, dstIp, dstPort);
    }

    public static final class Builder {
        private final byte[] srcIp;
        private final int srcPort;
        private final byte[] dstIp;
        private final int dstPort;

        private int seq;
        private int ack;
        private int flags;
        private int window = DEFAULT_WINDOW;
        private byte[] options;
        private ByteBuf payload;
        private int payloadLen;

        private Builder(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort) {
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
        }

        public Builder seq(int seq) { this.seq = seq; return this; }
        public Builder ack(int ack) { this.ack = ack; return this; }
        public Builder flags(int flags) { this.flags = flags; return this; }
        public Builder window(int window) { this.window = window; return this; }
        public Builder options(byte[] options) { this.options = options; return this; }
        public Builder payload(ByteBuf payload) {
            this.payload = payload;
            this.payloadLen = payload == null ? 0 : payload.readableBytes();
            return this;
        }

        public ByteBuf build() {
            return TcpPacketBuilder.buildRaw(srcIp, srcPort, dstIp, dstPort,
                    seq, ack, flags, window, options, payload, payloadLen);
        }
    }

    // ---- Shortcut builders ----

    /** 客户端 → 服务端 SYN(isn = 客户端初始序列号)。 */
    public static ByteBuf syn(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort, int isn) {
        return tcp(srcIp, srcPort, dstIp, dstPort).seq(isn).flags(TCP_FLAG_SYN).build();
    }

    /** 客户端 → 服务端 ACK(纯 ACK,无 payload)。 */
    public static ByteBuf ack(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort,
                              int seq, int ack) {
        return tcp(srcIp, srcPort, dstIp, dstPort).seq(seq).ack(ack).flags(TCP_FLAG_ACK).build();
    }

    /** 客户端 → 服务端 数据段(ACK + PSH + payload)。 */
    public static ByteBuf data(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort,
                               int seq, int ack, ByteBuf payload) {
        return tcp(srcIp, srcPort, dstIp, dstPort)
                .seq(seq).ack(ack)
                .flags(TCP_FLAG_ACK | TCP_FLAG_PSH)
                .payload(payload)
                .build();
    }

    /** 客户端 → 服务端 FIN(正常半关)。 */
    public static ByteBuf fin(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort,
                              int seq, int ack) {
        return tcp(srcIp, srcPort, dstIp, dstPort).seq(seq).ack(ack)
                .flags(TCP_FLAG_ACK | TCP_FLAG_FIN).build();
    }

    /** 客户端 → 服务端 RST。 */
    public static ByteBuf rst(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort, int seq) {
        return tcp(srcIp, srcPort, dstIp, dstPort).seq(seq).flags(TCP_FLAG_RST).build();
    }
}
