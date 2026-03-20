# tun.net 优化设计文档

> 包路径：`com.github.pangolin.routing.acceptor.tun.net`
> 分析日期：2026-03-20

---

## Part 1 — ByteBuf 替换 pcap4j 改造方案

### 1.1 背景

当前流水线的**接收侧**通过 `IpPacketCodec` 将原始 `ByteBuf` 解析为 pcap4j 的 `IpV4Packet` / `TcpPacket` 对象，**发送侧**所有包（SYN-ACK、RST、ACK-only 及数据包）均通过 pcap4j Builder 组装再序列化为字节流（`_INDIRECT_CALL_INET` 为唯一发送路径）。

**本次改造的目标**：引入 `IpPacketBuf` 轻量封装替代 pcap4j 对象，发送侧改为直接写 `ByteBuf`、手算校验和，彻底移除 pcap4j 依赖。

### 1.2 当前 pcap4j 使用点全景

| 位置 | 用途 | 替换难度 |
|------|------|---------|
| `IpPacketCodec` | `ByteBuf → IpPacket` 解析；`IpPacket → ByteBuf` 编码 | 低 |
| `IpPacketHandler` | 按 `IpPacket` 类型 + `IpNumber` 协议分派 | 低 |
| `TcpDemultiplexHandler` | 泛型参数 `<T extends IpPacket>`；异常路径调 `send_reset` | 低 |
| `Tcp4DemultiplexHandler.prepare()` | 用 pcap4j Builder 替换 dst/src 地址 | 中 |
| `Tcp4Demultiplexer.tcp_v4_rcv()` | `ipPacket.get(TcpPacket.class)` 取 TCP 子包 | 低 |
| `Tcp4Demultiplexer.__inet_lookup_skb()` | 从 `IpHeader` / `TcpHeader` 取四元组 | 低 |
| `Tcp4Demultiplexer._INDIRECT_CALL_INET()` | pcap4j 路径：SYN/FIN/ACK-only/带选项包 | **高** |
| `Tcp4Demultiplexer.tcp_v4_send_reset()` | pcap4j 构造 RST | 中 |
| `Tcp4Demultiplexer.tcp_v4_send_synack()` | pcap4j 构造 SYN-ACK | 中 |
| `TcpBuffer.options` | `List<TcpPacket.TcpOption>`（pcap4j 类型） | 中 |
| `TcpBuffer.payloadBuilder` | `Packet.Builder`（pcap4j 类型） | 中 |
| `TcpBuffer.asBuilder()` | 转回 pcap4j `TcpPacket.Builder` | 中 |
| `TcpOutput` 选项构建 | `MssOption`、`NopOption`、`SackPermittedOption` 等 pcap4j 类 | 中 |
| `TcpInput` 选项解析 | 读取 `TcpPacket.TcpOption` 列表 | 中 |

---

### 1.3 整体设计思路

```
当前链路（接收）：
  ByteBuf ──IpPacketCodec──► IpV4Packet ──IpPacketHandler──► Tcp4DemultiplexHandler
                pcap4j 解析                pcap4j 类型检查

目标链路（接收）：
  ByteBuf ──IpPacketCodec──► IpPacketBuf ──IpPacketHandler──► Tcp4DemultiplexHandler
              薄封装，不解析                  字节级协议分派

当前链路（发送控制包）：
  TcpBuffer ──asBuilder()──► TcpPacket.Builder ──► IpV4Packet.Builder ──serialize──► ByteBuf
                pcap4j 构建 + 校验和

目标链路（发送控制包）：
  TcpBuffer ──PacketBuilder──► CompositeByteBuf（直接写入，手算校验和）──► TUN
                统一 ByteBuf 路径
```

引入两个新类，其余文件做改造：

| 新增/改造 | 说明 |
|----------|------|
| **`IpPacketBuf`**（新增） | 轻量 ByteBuf 封装，提供所有 IP/TCP 字段读写访问器，替代 `IpV4Packet`/`TcpPacket` |
| **`TcpOptionCodec`**（新增） | TCP 选项的原始字节编解码，替代 pcap4j option 对象 |
| `IpPacketCodec` | 移除 pcap4j；解码侧仅封装为 `IpPacketBuf` |
| `IpPacketHandler` | 泛型改为 `IpPacketBuf`；协议分派改为字节级读取 |
| `TcpBuffer` | 移除 `TcpPort`/`Packet.Builder`/`TcpOption`；改用原生类型 + 原始字节 |
| `TcpDemultiplexHandler` | 泛型改为 `IpPacketBuf` |
| `Tcp4DemultiplexHandler` | `prepare()` 改为字节级地址替换 |
| `Tcp4Demultiplexer` | 接收/发送全部改用 `IpPacketBuf` + 直接 ByteBuf 写 |
| `TcpOutput` | 选项构建改为原始字节 |
| `TcpInput` | 选项解析改为原始字节 |

---

### 1.4 新增类设计

#### 1.4.1 `IpPacketBuf`

```
package com.github.pangolin.routing.acceptor.tun.net.handler.support
```

封装一整个 IP 包的 `ByteBuf`，提供**无拷贝**字段访问。所有偏移基于绝对字节位置，不移动 `readerIndex`（使用 `getXxx`）。

```
IPv4 报文结构（偏移均从 buf.readerIndex() 起算）：

 字节  0: version(4bit) + IHL(4bit)
 字节  1: DSCP/ECN
 字节  2-3: total length
 字节  4-5: identification
 字节  6-7: flags + fragment offset
 字节  8: TTL
 字节  9: protocol (6=TCP, 17=UDP)
 字节 10-11: IP checksum
 字节 12-15: src IP
 字节 16-19: dst IP
 字节 20+:   payload（TCP header starts here when IHL=5）

TCP header（相对 IP payload 起点，即 ihl() 字节处）：
 +0-1: src port
 +2-3: dst port
 +4-7: sequence number
 +8-11: acknowledgment number
 +12:  data offset(4bit,高) + reserved(3bit) + NS(1bit)
 +13:  CWR URG ACK PSH RST SYN FIN flags
 +14-15: window size
 +16-17: TCP checksum
 +18-19: urgent pointer
 +20~(dataOffset-1): TCP options
 +dataOffset+: payload
```

**接口设计：**

```java
public final class IpPacketBuf {

    public static final byte PROTO_TCP = 6;
    public static final byte PROTO_UDP = 17;

    private final ByteBuf buf;          // 持有 retain，生命周期与此对象一致
    private InetAddress resolvedDstAddr; // DNS 反查后的目标地址（仅保存元数据，不修改 buf）

    // ---- 构造 ----
    public static IpPacketBuf wrap(ByteBuf buf) { ... }         // buf 已 retain
    public static IpPacketBuf retainedWrap(ByteBuf buf) { ... } // 内部 retain 一次

    // ---- IPv4 字段（只读，直接读 buf） ----
    public int  version()    // (buf[0] >> 4) & 0xF
    public int  ihl()        // (buf[0] & 0xF) * 4  → IP header 字节数
    public byte protocol()   // buf[9]
    public int  totalLength()// buf.getUnsignedShort(2)
    public byte ttl()        // buf[8]
    public short ipId()      // buf.getShort(4)
    public short ipFlags()   // buf.getShort(6)
    public byte[] srcAddrBytes()  // buf[12..15]
    public byte[] dstAddrBytes()  // buf[16..19]
    public InetAddress srcAddr()
    public InetAddress dstAddr()

    // ---- TCP 字段（只读，偏移 = ihl()） ----
    public int  tcpSrcPort()  // getUnsignedShort(ihl() + 0)
    public int  tcpDstPort()  // getUnsignedShort(ihl() + 2)
    public int  tcpSeq()      // getInt(ihl() + 4)
    public int  tcpAckNum()   // getInt(ihl() + 8)
    public int  tcpDataOffset()  // ((buf[ihl()+12] >> 4) & 0xF) * 4 → TCP header 字节数
    public int  tcpFlags()    // buf[ihl()+13] & 0xFF
    public int  tcpWindow()   // getUnsignedShort(ihl() + 14)
    public boolean isSyn()    public boolean isAck()    public boolean isFin()
    public boolean isRst()    public boolean isPsh()    public boolean isUrg()

    // ---- TCP options（原始字节切片，不拷贝） ----
    public ByteBuf tcpOptionsSlice()  // buf.slice(ihl()+20, tcpDataOffset()-20)

    // ---- TCP payload（原始字节切片） ----
    public ByteBuf tcpPayloadSlice()  // buf.slice(ihl()+tcpDataOffset(), payloadLen)
    public int tcpPayloadLength()

    // ---- 地址元数据（DNS 反查结果，不修改 buf） ----
    public InetAddress resolvedDstAddr()
    public IpPacketBuf resolvedDstAddr(InetAddress addr)

    // ---- 底层 buf ----
    public ByteBuf buf()
    public void release()
}
```

> **关键决策**：`prepare()` 阶段 DNS 反查的目标地址**不写回 buf**（避免校验和失效），而是存为 `resolvedDstAddr` 字段。后续 `Tcp4Demultiplexer` 建立连接时直接使用 `resolvedDstAddr()`；发送方向的 src/dst 地址来自 `TcpSock.ir_loc_addr / ir_rmt_addr`，与接收侧无关。

---

#### 1.4.2 `TcpOptionCodec`

```
package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util
```

负责 TCP Options 的**解析**（输入侧）和**构建**（输出侧），完全操作原始 `byte[]` / `ByteBuf`。

```java
public final class TcpOptionCodec {

    // ---- 解析 ----

    /** 从 TCP options 原始字节中提取 MSS（Option Kind=2），未找到返回 -1 */
    public static int parseMss(ByteBuf opts) { ... }

    /** 提取窗口缩放因子（Kind=3），未找到返回 -1 */
    public static int parseWindowScale(ByteBuf opts) { ... }

    /** 检查是否含 SACK Permitted（Kind=4） */
    public static boolean hasSackPermitted(ByteBuf opts) { ... }

    /** 提取时间戳 [tsval, tsecr]，未找到返回 null */
    public static long[] parseTimestamp(ByteBuf opts) { ... }

    // ---- 构建 ----

    /**
     * 构建 SYN/SYN-ACK 的 options 字节序列，写入给定 ByteBuf。
     * 包含：MSS(4B) + NOP(1B) + Window Scale(3B) + NOP(1B) + NOP(1B) + SACK Permitted(2B)
     * 共 12 字节，已 4 字节对齐。
     *
     * @param mss       本端 MSS 值
     * @param wscale    本端窗口缩放因子（0~14），-1 表示不发送此选项
     */
    public static void writeSynOptions(ByteBuf out, int mss, int wscale) { ... }

    /**
     * 构建 established 状态下的 options（时间戳），写入给定 ByteBuf。
     * 包含：NOP(1B) + NOP(1B) + Timestamp(10B) = 12 字节
     */
    public static void writeTimestampOption(ByteBuf out, long tsval, long tsecr) { ... }

    // ---- TCP options 格式常量 ----
    public static final byte OPT_EOL   = 0;
    public static final byte OPT_NOP   = 1;
    public static final byte OPT_MSS   = 2;
    public static final byte OPT_WSCALE= 3;
    public static final byte OPT_SACK_PERMITTED = 4;
    public static final byte OPT_SACK  = 5;
    public static final byte OPT_TIMESTAMP = 8;
}
```

---

### 1.5 改造各文件详细说明

#### 1.5.1 `IpPacketCodec.java`

**目标**：移除 pcap4j，解码侧封装 `IpPacketBuf`，编码侧直接写出 ByteBuf。

**改造前（核心逻辑）：**
```java
// 解码：ByteBuf → IpPacket（pcap4j）
byte[] bytes = new byte[packet.readableBytes()];
packet.readBytes(bytes);
RAW_BYTES.set(bytes);
out.add(IpSelector.newPacket(bytes, 0, bytes.length));

// 编码：IpPacket → ByteBuf
out.writeBytes(msg.getRawData());
```

**改造后：**
```java
// 解码：ByteBuf → IpPacketBuf（零拷贝封装）
// retain 一次，IpPacketBuf 持有所有权；IpPacketHandler.channelRead 消费后 release
out.add(IpPacketBuf.retainedWrap(packet.readRetainedSlice(packet.readableBytes())));

// 编码：ByteBuf 直接透传（sendDirect / 控制包已写入 ByteBuf，无需转换）
out.writeBytes((ByteBuf) msg);
```

移除：
- `RAW_BYTES` ThreadLocal（不再需要）
- `IpSelector` / `IllegalRawDataException` 依赖
- 泛型参数 `ByteToMessageCodec<IpPacket>` → `ByteToMessageCodec<ByteBuf>`

---

#### 1.5.2 `IpPacketHandler.java`

**目标**：移除 pcap4j，改为字节级协议分派。

**改造前：**
```java
public abstract class IpPacketHandler<T extends IpPacket> extends ChannelDuplexHandler {
    private final IpNumber ipProtocol;
    private final Class<T> ipPacketType;

    protected boolean accept(Object msg) {
        if (!ipPacketType.isInstance(msg)) return false;
        return iph.getProtocol().equals(ipProtocol);
    }
    // 处理完后 IpPacketCodec.RAW_BYTES.remove()
}
```

**改造后：**
```java
// 泛型直接移除，统一使用 IpPacketBuf
public abstract class IpPacketHandler extends ChannelDuplexHandler {
    private final byte ipProtocol;  // 6=TCP, 17=UDP

    protected boolean accept(IpPacketBuf pkt) {
        return pkt.version() == 4 && pkt.protocol() == ipProtocol;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof IpPacketBuf) {
            IpPacketBuf pkt = (IpPacketBuf) msg;
            if (accept(pkt)) {
                try {
                    channelRead0(ctx, pkt);
                } finally {
                    pkt.release();  // handler 消费后释放
                }
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    protected abstract void channelRead0(ChannelHandlerContext ctx, IpPacketBuf pkt) throws Exception;
}
```

移除：
- `IpNumber` / pcap4j 依赖
- `ReferenceCountUtil.release(msg)`（改为 `pkt.release()`）
- `IpPacketCodec.RAW_BYTES.remove()`

---

#### 1.5.3 `TcpDemultiplexHandler.java`

**目标**：移除泛型 IpPacket，改为 `IpPacketBuf`。

**改造前：**
```java
public abstract class TcpDemultiplexHandler<T extends IpPacket> extends IpPacketHandler<T> {
    protected void channelRead0(ChannelHandlerContext ctx, T rawIpPacket) throws Exception {
        T ipPacket = prepare(rawIpPacket);
        demultiplexer.tcp_rcv(ctx.channel(), ipPacket);
        // 异常时：rawIpPacket.get(TcpPacket.class) 取 TCP 包发 reset
    }
}
```

**改造后：**
```java
public abstract class TcpDemultiplexHandler extends IpPacketHandler {
    protected void channelRead0(ChannelHandlerContext ctx, IpPacketBuf rawPkt) throws Exception {
        IpPacketBuf pkt = prepare(rawPkt);
        demultiplexer.tcp_rcv(ctx.channel(), pkt);
        // 异常时：直接用 pkt 的字节级信息发 reset（无需 TcpPacket 对象）
    }

    protected IpPacketBuf prepare(IpPacketBuf pkt) throws UnknownHostException {
        return pkt;  // 默认不处理
    }
}
```

---

#### 1.5.4 `Tcp4DemultiplexHandler.java`

**目标**：`prepare()` 从 ByteBuf 层面解析 dst 地址，DNS 反查后保存到 `IpPacketBuf.resolvedDstAddr`，**不重建包对象**。

**改造前：**
```java
protected IpV4Packet prepare(IpV4Packet ipPacket) throws UnknownHostException {
    // 用 pcap4j Builder 重建整个包，替换 src/dst 地址
    return ipPacket.getBuilder()
        .srcAddr((Inet4Address) noDnsQuery(iph.getSrcAddr()))
        .dstAddr((Inet4Address) resolveDstAddress(dstAddr))
        .build();  // 触发完整序列化 + 校验和重算
}
```

**改造后：**
```java
protected IpPacketBuf prepare(IpPacketBuf pkt) throws UnknownHostException {
    InetAddress dstAddr = pkt.dstAddr();
    InetAddress resolved = resolveDstAddress(dstAddr);
    return pkt.resolvedDstAddr(resolved);  // 仅设置元数据，不修改 buf，O(1)
}
```

优化效果：去掉了每个收到包都触发的 pcap4j 完整序列化重建（包含校验和重算）。

---

#### 1.5.5 `Tcp4Demultiplexer.java`

这是改动最大的文件，分四个子区域：

##### A. 接收侧解析（`tcp_v4_rcv` 等）

**改造前：**
```java
TcpPacket tcpPacket = ipPacket.get(TcpPacket.class);
TcpPacket.TcpHeader th = tcpPacket.getHeader();
String key = uniqueKey(iph, th);  // 用 pcap4j 对象查键
```

**改造后：**
```java
// IpPacketBuf 直接提供所有字段
if (!pkt.isTcp()) { ... return; }
String key = uniqueKey(pkt);  // TcpUtils.uniqueKey 改为接受 IpPacketBuf
```

`TcpUtils.uniqueKey` 修改为：
```java
// 改造前：uniqueKey(IpHeader iph, TcpHeader th)
// 改造后：
public static String uniqueKey(IpPacketBuf pkt) {
    return pkt.srcAddr().getHostAddress() + ":" + pkt.tcpSrcPort()
         + "->" + pkt.dstAddr().getHostAddress() + ":" + pkt.tcpDstPort();
}
```

##### B. 发送路径统一（`_INDIRECT_CALL_INET`）

当前只有一条路径：全部走 pcap4j（`TcpBuffer.asBuilder()` → `IpV4Packet.Builder` → 序列化）。

**改造后，替换为直接 ByteBuf 写入**——所有包（含 SYN/FIN/ACK-only 及数据包）统一走 `sendRaw()`。

**常量定义**（建议放在 `Tcp4Demultiplexer` 类顶部，或抽取到 `IpPacketBuf` / `TcpConstants`）：

```java
// ---- IPv4 header 固定字段 ----
private static final int   IP_HEADER_LEN      = 20;    // 无选项时 IPv4 固定头长度
private static final byte  IP_VERSION_IHL     = 0x45;  // version=4, IHL=5（无 IP options）
private static final byte  IP_DSCP_ECN        = 0x00;  // 默认 DSCP/ECN
private static final short IP_FLAGS_DF        = 0x4000;// Don't Fragment，fragment offset=0
private static final byte  IP_DEFAULT_TTL     = 64;    // 默认 TTL
private static final byte  IP_PROTO_TCP       = 0x06;  // protocol = TCP
private static final int   IP_CHECKSUM_OFFSET = 10;    // IP 校验和字段在 IP 头内的偏移

// ---- TCP header 固定字段 ----
private static final int   TCP_MIN_HEADER_LEN      = 20; // 无选项时 TCP 固定头长度
private static final int   TCP_CHECKSUM_OFFSET     = 16; // TCP 校验和字段相对 TCP 头起点的偏移

// ---- TCP flags 掩码 ----
private static final int   TCP_FLAG_URG = 0x20;
private static final int   TCP_FLAG_ACK = 0x10;
private static final int   TCP_FLAG_PSH = 0x08;
private static final int   TCP_FLAG_RST = 0x04;
private static final int   TCP_FLAG_SYN = 0x02;
private static final int   TCP_FLAG_FIN = 0x01;
```

**`_INDIRECT_CALL_INET` 与 `sendRaw` 实现：**

```java
protected static void _INDIRECT_CALL_INET(Channel net, TcpSock tp,
                                           IpPacketBuf incoming, TcpBuffer skb) {
    final Inet4Address srcAddr = (Inet4Address) tp.ir_rmt_addr;
    final Inet4Address dstAddr = (Inet4Address) tp.ir_loc_addr;
    skb.srcAddr(dstAddr).dstAddr(srcAddr);
    sendRaw(net, skb, srcAddr, dstAddr);
}

private static void sendRaw(Channel net, TcpBuffer skb,
                             Inet4Address srcAddr, Inet4Address dstAddr) {
    // 1. 计算各段长度
    byte[] opts    = skb.rawOptions();
    int optsLen    = (opts != null) ? opts.length : 0;
    int tcpHdrLen  = TCP_MIN_HEADER_LEN + optsLen;   // 已 4 字节对齐（由 TcpOutput 保证）
    int payloadLen = skb.payloadLength();
    int totalLen   = IP_HEADER_LEN + tcpHdrLen + payloadLen;

    // 2. 分配头部 buffer（IP header + TCP header）
    ByteBuf headers = net.alloc().buffer(IP_HEADER_LEN + tcpHdrLen,
                                         IP_HEADER_LEN + tcpHdrLen);
    byte[] src4 = srcAddr.getAddress();
    byte[] dst4 = dstAddr.getAddress();

    // 3. 写 IPv4 头（IP_HEADER_LEN 字节）
    headers.writeByte(IP_VERSION_IHL);
    headers.writeByte(IP_DSCP_ECN);
    headers.writeShort(totalLen);                    // total length
    headers.writeShort(0);                           // identification
    headers.writeShort(IP_FLAGS_DF);
    headers.writeByte(IP_DEFAULT_TTL);
    headers.writeByte(IP_PROTO_TCP);
    headers.writeShort(0);                           // checksum placeholder
    headers.writeBytes(src4);
    headers.writeBytes(dst4);

    // 4. 写 TCP 头（TCP_MIN_HEADER_LEN 字节固定 + options）
    //    TCP 头在 headers 中的起始偏移 = IP_HEADER_LEN
    headers.writeShort(skb.srcPort());
    headers.writeShort(skb.dstPort());
    headers.writeInt(skb.sequenceNumber());
    headers.writeInt(skb.acknowledgmentNumber());
    headers.writeByte((tcpHdrLen / 4) << 4);         // data offset（单位：4 字节）
    int flags = 0;
    if (skb.urg()) flags |= TCP_FLAG_URG;
    if (skb.ack()) flags |= TCP_FLAG_ACK;
    if (skb.psh()) flags |= TCP_FLAG_PSH;
    if (skb.rst()) flags |= TCP_FLAG_RST;
    if (skb.syn()) flags |= TCP_FLAG_SYN;
    if (skb.fin()) flags |= TCP_FLAG_FIN;
    headers.writeByte(flags);
    headers.writeShort(skb.window() & 0xFFFF);
    headers.writeShort(0);                           // TCP checksum placeholder
    headers.writeShort(0);                           // urgent pointer
    if (optsLen > 0) headers.writeBytes(opts);

    // 5. 计算 TCP 校验和（伪头部 + TCP 头 + payload）
    int tcpSegLen = tcpHdrLen + payloadLen;
    long sum = checksumPseudo(src4, dst4, IP_PROTO_TCP, tcpSegLen);
    sum = checksumBuf(sum, headers, IP_HEADER_LEN, tcpHdrLen);
    if (skb.rawPayload() != null) {
        sum = checksumBuf(sum, skb.rawPayload(), skb.rawPayload().readerIndex(), payloadLen);
    }
    headers.setShort(IP_HEADER_LEN + TCP_CHECKSUM_OFFSET, (int) (~foldChecksum(sum) & 0xFFFF));

    // 6. 计算 IP 校验和
    sum = checksumBuf(0, headers, 0, IP_HEADER_LEN);
    headers.setShort(IP_CHECKSUM_OFFSET, (int) (~foldChecksum(sum) & 0xFFFF));

    // 7. 组装 CompositeByteBuf（headers + 可选 payload 零拷贝）
    CompositeByteBuf packet = net.alloc().compositeBuffer(skb.rawPayload() != null ? 2 : 1);
    packet.addComponent(true, headers);
    if (skb.rawPayload() != null) {
        packet.addComponent(true, skb.rawPayload().retainedSlice());
    }

    net.writeAndFlush(packet);
}
```

校验和辅助方法提取到 `TcpUtils`（或 `Tcp4Demultiplexer` 内 private static）：
```java
static long checksumPseudo(byte[] src, byte[] dst, byte proto, int tcpSegLen) { ... }
static long checksumBuf(long init, ByteBuf buf, int offset, int len) { ... }
static long foldChecksum(long sum) { while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16); return sum; }
```

##### C. 发送 RST（`tcp_v4_send_reset`）

**改造前**：pcap4j Builder → `IpV4Packet` → `net.writeAndFlush(ipPacket)`

**改造后**：直接用 `sendRaw()`，构建 RST 的 `TcpBuffer`：
```java
static void tcp_v4_send_reset(Channel net, IpPacketBuf pkt, int err) {
    log.warn("SEND-RST: {}", err);

    boolean isAck = pkt.isAck();
    TcpBuffer rst = new TcpBuffer()
        .srcPort(pkt.tcpDstPort())
        .dstPort(pkt.tcpSrcPort())
        .rst(true)
        .ack(!isAck)
        .sequenceNumber(isAck ? pkt.tcpAckNum() : 1)
        .acknowledgmentNumber(isAck ? 0 : pkt.tcpSeq() + pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0));

    Inet4Address src = (Inet4Address) pkt.dstAddr();
    Inet4Address dst = (Inet4Address) pkt.srcAddr();
    rst.srcAddr(src).dstAddr(dst);
    sendRaw(net, rst, src, dst);
}
```

##### D. 发送 SYN-ACK（`tcp_v4_send_synack`）

**改造前**：pcap4j `TcpPacket.Builder` + `IpV4Packet.Builder`

**改造后**：
```java
protected void tcp_v4_send_synack(Channel net, TcpSock listenSock,
                                   tcp_request_sock req, IpPacketBuf syn) {
    Inet4Address locAddr = (Inet4Address) req.ir_loc_addr;
    Inet4Address rmtAddr = (Inet4Address) req.ir_rmt_addr;

    // output.tcp_make_synack 改为返回携带 rawOptions 的 TcpBuffer
    TcpBuffer synack = output.tcp_make_synack(listenSock, req, syn)
        .srcPort(syn.tcpDstPort())
        .dstPort(syn.tcpSrcPort())
        .srcAddr(locAddr)
        .dstAddr(rmtAddr);

    log.warn("SYNACK send starting...");
    sendRaw(net, synack, rmtAddr, locAddr);
}
```

---

#### 1.5.6 `TcpBuffer.java`

**目标**：移除所有 pcap4j 类型，改用原生类型。

| 字段 | 改造前 | 改造后 |
|------|-------|-------|
| `srcPort` / `dstPort` | `TcpPort`（pcap4j） | `int` |
| `options` | `List<TcpPacket.TcpOption>`（pcap4j） | `byte[] rawOptions` |
| `payloadBuilder` | `Packet.Builder`（pcap4j） | **删除**（统一用 `rawPayload`） |
| `asBuilder()` | 返回 pcap4j Builder | **删除** |

**关键变化：**

```java
// 改造前
private TcpPort srcPort;
private List<TcpPacket.TcpOption> options;
private Packet.Builder payloadBuilder;

public TcpPacket.Builder asBuilder() { ... }  // 删除

// 改造后
private int srcPort;
private int dstPort;
private byte[] rawOptions;   // 已序列化为字节，含 NOP padding（4 字节对齐）

// payloadLength() 简化：
public int payloadLength() {
    return rawPayload != null ? rawPayload.readableBytes() : 0;
}
// payloadBuilder 路径完全移除（原来仅在非 rawPayload 控制包使用，
// 现在控制包的 payload 都是空的，直接 length=0）
```

`cachedPayloadLength` 字段随 `payloadBuilder` 一起删除。

---

#### 1.5.7 `TcpOutput.java`

**目标**：选项构建改用 `TcpOptionCodec`，移除 pcap4j option 类。

**改造前（选项构建片段）：**
```java
import org.pcap4j.packet.TcpMaximumSegmentSizeOption;
import org.pcap4j.packet.TcpWindowScaleOption;
// ...
List<TcpOption> opts = new ArrayList<>();
opts.add(new TcpMaximumSegmentSizeOption.Builder().maxSegSize((short) mss).build());
opts.add(new TcpNoOperationOption());
opts.add(new TcpWindowScaleOption.Builder().shiftCount((byte) wscale).build());
skb.options(opts);
```

**改造后：**
```java
// tcp_synack_options / tcp_established_options 改为向 ByteBuf/byte[] 写入
ByteBuf optBuf = ctx.alloc().buffer(12);
TcpOptionCodec.writeSynOptions(optBuf, mss, wscale);
skb.rawOptions(optBuf.array());  // 或 readBytes 到 byte[]
optBuf.release();
```

`tcp_make_synack()` 返回类型不变（`TcpBuffer`），但内部不再调用 `asBuilder()`。

---

#### 1.5.8 `TcpInput.java`

**目标**：TCP 选项解析改为原始字节解析。

**改造前：**
```java
// 从 pcap4j TcpPacket 对象读取选项
List<TcpOption> opts = tcpPacket.getHeader().getOptions();
for (TcpOption opt : opts) {
    if (opt instanceof TcpMaximumSegmentSizeOption) { ... }
    if (opt instanceof TcpWindowScaleOption) { ... }
}
```

**改造后：**
```java
// 从 IpPacketBuf 直接获取选项字节切片
ByteBuf optSlice = pkt.tcpOptionsSlice();
int mss = TcpOptionCodec.parseMss(optSlice);
int wscale = TcpOptionCodec.parseWindowScale(optSlice);
boolean sackOk = TcpOptionCodec.hasSackPermitted(optSlice);
// optSlice 是 buf 的 slice，不需要 release
```

`tcp_parse_options()` 方法签名从接受 `TcpPacket` 改为接受 `IpPacketBuf`（或直接 `ByteBuf opts`）。

---

### 1.6 IPv4/TCP 报文头字节布局速查

```
IPv4 Header（固定 20 字节，IHL=5）：
 Byte  0:  Version(4) + IHL(4)          → 0x45
 Byte  1:  DSCP/ECN                      → 0x00
 Byte  2-3: Total Length（含 IP header）
 Byte  4-5: Identification
 Byte  6-7: Flags(3) + Fragment Offset(13)
 Byte  8:  TTL
 Byte  9:  Protocol（TCP=6, UDP=17）
 Byte 10-11: Header Checksum             ← 需设为 0 再计算
 Byte 12-15: Source IP
 Byte 16-19: Destination IP

TCP Header（固定 20 字节 + options）：
 Byte  0-1: Source Port
 Byte  2-3: Destination Port
 Byte  4-7: Sequence Number
 Byte  8-11: Acknowledgment Number
 Byte 12:  Data Offset(4,高) + Reserved(3) + NS(1)
 Byte 13:  CWR(1) URG(1) ACK(1) PSH(1) RST(1) SYN(1) FIN(1)  ← 低 6 位
 Byte 14-15: Window Size
 Byte 16-17: Checksum                   ← 需设为 0 再计算
 Byte 18-19: Urgent Pointer
 Byte 20+:  Options（4 字节对齐）

TCP Checksum 伪头部（12 字节）：
 Source IP (4B) + Dest IP (4B) + 0x00 (1B) + Protocol=6 (1B) + TCP Segment Length (2B)
```

---

### 1.7 实施顺序

改造存在依赖关系，建议按以下顺序逐步进行，每步保持可编译可运行：

```
Step 1  新增 IpPacketBuf                    （无依赖）
Step 2  新增 TcpOptionCodec                 （无依赖）
Step 3  改造 TcpBuffer                      （移除 pcap4j 字段，改 int 端口 + rawOptions）
Step 4  改造 TcpOutput 选项构建              （依赖 TcpOptionCodec + TcpBuffer）
Step 5  改造 TcpInput 选项解析               （依赖 TcpOptionCodec）
Step 6  改造 IpPacketCodec                  （改为 IpPacketBuf 输出）
Step 7  改造 IpPacketHandler                （依赖 IpPacketBuf）
Step 8  改造 TcpDemultiplexHandler          （依赖 IpPacketHandler 改造）
Step 9  改造 Tcp4DemultiplexHandler.prepare()（依赖 IpPacketBuf）
Step 10 改造 Tcp4Demultiplexer              （最大改动，依赖所有前序步骤）
          10a: 接收侧解析改为 IpPacketBuf 访问器
          10b: sendRaw() 统一发送路径
          10c: tcp_v4_send_reset 改造
          10d: tcp_v4_send_synack 改造
          10e: 删除 _INDIRECT_CALL_INET pcap4j 分支
```

每一步完成后应通过回归测试（握手、数据传输、FIN 四次挥手、RST）验证。

---

### 1.8 收益与影响

| 维度 | 改造前 | 改造后 |
|------|-------|-------|
| 接收侧每包对象分配 | `byte[]` + `IpV4Packet` + `TcpPacket` + 各 Header 对象 | 仅 `IpPacketBuf`（薄封装，无字段拷贝） |
| `prepare()` 开销 | pcap4j 完整重建：序列化 + 校验和重算 | `O(1)` 元数据设置 |
| 发送侧所有包路径 | pcap4j Builder → serialize → `byte[]` → `ByteBuf` | 直接写 `ByteBuf`，无中间对象，控制包与数据包统一 `sendRaw` |
| TCP 选项处理 | pcap4j Option 对象列表，含反射 | 原始字节，无反射，无装箱 |
| pcap4j 依赖 | 全量依赖 | **可移除** |
| `RAW_BYTES` ThreadLocal | 需要（零拷贝 hack） | 不再需要 |

---

### 1.9 IPv4 含选项 与 IPv6 支持分析

#### 1.9.1 IPv4 含选项

| 路径 | 结论 | 说明 |
|------|------|------|
| **接收侧解析** | ✅ 已支持 | `IpPacketBuf.ihl()` 读取实际 IHL 字段 `(buf[0] & 0xF) * 4`，所有 TCP 字段偏移均基于 `ihl()`，不受 IP options 长度影响 |
| **发送侧构造** | ✅ 可接受 | `sendRaw` 始终写 `IP_VERSION_IHL = 0x45`（IHL=5，无 IP options）；我们发送的是**响应包**，无需复制来包的 IP options，这是标准行为 |

无需额外改动。

---

#### 1.9.2 IPv6 支持

当前设计全部基于 IPv4，主要差异如下：

```
IPv6 固定头（40 字节）：
 Byte  0-3:  Version(4) + Traffic Class(8) + Flow Label(20)
 Byte  4-5:  Payload Length（不含 40B 固定头）
 Byte  6:    Next Header（TCP=6）← protocol 字段位置不同
 Byte  7:    Hop Limit          ← TTL 等价字段
 Byte  8-23: Source Address（16 字节）← 地址长度/偏移均不同
 Byte 24-39: Destination Address（16 字节）
 Byte 40+:   TCP header（假设无扩展头）

IPv6 TCP 校验和伪头部（40 字节）：
 Source Address   (16B)
 Dest Address     (16B)
 TCP Length        (4B)   ← 注意是 4 字节，IPv4 是 2 字节
 Zeros             (3B)
 Next Header = 6   (1B)

IPv6 无 IP header checksum（不需要计算 IP 校验和）
```

当前设计中与 IPv6 **不兼容**的具体位置：

| 位置 | 问题 |
|------|------|
| `IpPacketBuf.ihl()` | 用 IPv4 IHL 字段计算头长，IPv6 固定头 40B |
| `IpPacketBuf.protocol()` | IPv4 读 `buf[9]`；IPv6 Next Header 在 `buf[6]` |
| `IpPacketBuf.srcAddrBytes()` | IPv4 `buf[12..15]`（4B）；IPv6 `buf[8..23]`（16B） |
| `IpPacketBuf.dstAddrBytes()` | IPv4 `buf[16..19]`（4B）；IPv6 `buf[24..39]`（16B） |
| `sendRaw` | 写 IPv4 头；伪头部 src/dst 各 4B；计算 IP checksum |
| `tcp_v4_send_reset/synack` | 强转 `Inet4Address` |
| 常量 `IP_VERSION_IHL` / `IP_CHECKSUM_OFFSET` | IPv4 专用 |

---

#### 1.9.3 IPv6 扩展方案

##### A. `IpPacketBuf` 改为抽象基类

```java
// 抽象基类，只保留版本无关的公共接口
public abstract class IpPacketBuf {
    protected final ByteBuf buf;

    public int version() { return (buf.getByte(base()) >> 4) & 0xF; }

    // 子类实现：IP 头长度（含扩展头）
    public abstract int  ipHeaderLen();
    // 子类实现：上层协议号（TCP=6, UDP=17）
    public abstract byte nextProto();
    // 子类实现：TTL / Hop Limit
    public abstract int  hopLimit();
    // 子类实现：src/dst 字节数组（4B 或 16B）
    public abstract byte[] srcAddrBytes();
    public abstract byte[] dstAddrBytes();
    public abstract InetAddress srcAddr();
    public abstract InetAddress dstAddr();

    // TCP 字段偏移均基于 ipHeaderLen()，子类无需覆盖
    public int  tcpSrcPort()    { return buf.getUnsignedShort(ipHeaderLen()); }
    public int  tcpDstPort()    { return buf.getUnsignedShort(ipHeaderLen() + 2); }
    public int  tcpSeq()        { return buf.getInt(ipHeaderLen() + 4); }
    public int  tcpAckNum()     { return buf.getInt(ipHeaderLen() + 8); }
    public int  tcpDataOffset() { return ((buf.getByte(ipHeaderLen() + 12) >> 4) & 0xF) * 4; }
    public int  tcpFlags()      { return buf.getByte(ipHeaderLen() + 13) & 0xFF; }
    public int  tcpWindow()     { return buf.getUnsignedShort(ipHeaderLen() + 14); }
    // isSyn / isAck / ... 均不变
    public ByteBuf tcpOptionsSlice()  { return buf.slice(ipHeaderLen() + TCP_MIN_HEADER_LEN,
                                                         tcpDataOffset() - TCP_MIN_HEADER_LEN); }
    public ByteBuf tcpPayloadSlice()  { return buf.slice(ipHeaderLen() + tcpDataOffset(),
                                                         tcpPayloadLength()); }
    public int tcpPayloadLength()     { return buf.readableBytes() - ipHeaderLen() - tcpDataOffset(); }

    // 工厂方法：根据版本号自动选择子类
    public static IpPacketBuf retainedWrap(ByteBuf buf) {
        int version = (buf.getByte(buf.readerIndex()) >> 4) & 0xF;
        if (version == 4) return new Ip4PacketBuf(buf.retain());
        if (version == 6) return new Ip6PacketBuf(buf.retain());
        throw new IllegalArgumentException("Unsupported IP version: " + version);
    }
}
```

```java
// IPv4 子类
public final class Ip4PacketBuf extends IpPacketBuf {
    public int  ipHeaderLen()   { return (buf.getByte(base) & 0xF) * 4; }
    public byte nextProto()     { return buf.getByte(base + 9); }
    public int  hopLimit()      { return buf.getUnsignedByte(base + 8); }
    public byte[] srcAddrBytes(){ return readBytes(base + 12, 4); }
    public byte[] dstAddrBytes(){ return readBytes(base + 16, 4); }
    // ...
}
```

```java
// IPv6 子类（暂不支持扩展头，Next Header 必须直接为 TCP/UDP）
public final class Ip6PacketBuf extends IpPacketBuf {
    private static final int IP6_HEADER_LEN = 40;
    public int  ipHeaderLen()   { return IP6_HEADER_LEN; } // 暂不扫描扩展头
    public byte nextProto()     { return buf.getByte(base + 6); }
    public int  hopLimit()      { return buf.getUnsignedByte(base + 7); }
    public byte[] srcAddrBytes(){ return readBytes(base + 8,  16); }
    public byte[] dstAddrBytes(){ return readBytes(base + 24, 16); }
    // ...
}
```

> **注**：IPv6 扩展头（Hop-by-Hop、Routing、Fragment 等）会在固定头与 TCP 头之间插入额外字节，需递归跟随 Next Header 链直到找到 TCP(6)。当前设计暂假设无扩展头（`ipHeaderLen()` 固定返回 40），作为后续优化项跟踪。

---

##### B. `sendRaw` 按版本分发

```java
private static void sendRaw(Channel net, TcpBuffer skb,
                             InetAddress srcAddr, InetAddress dstAddr) {
    if (srcAddr instanceof Inet4Address) {
        sendRaw4(net, skb, (Inet4Address) srcAddr, (Inet4Address) dstAddr);
    } else {
        sendRaw6(net, skb, (Inet6Address) srcAddr, (Inet6Address) dstAddr);
    }
}
```

`sendRaw4` 即现有实现（不变）。`sendRaw6` 新增：

```java
// IPv6 新增常量
private static final int  IP6_HEADER_LEN        = 40;
private static final byte IP6_DEFAULT_HOP_LIMIT = 64;
// IPv6 无 IP header checksum，无需 IP6_CHECKSUM_OFFSET

private static void sendRaw6(Channel net, TcpBuffer skb,
                              Inet6Address srcAddr, Inet6Address dstAddr) {
    byte[] opts    = skb.rawOptions();
    int optsLen    = (opts != null) ? opts.length : 0;
    int tcpHdrLen  = TCP_MIN_HEADER_LEN + optsLen;
    int payloadLen = skb.payloadLength();
    int tcpSegLen  = tcpHdrLen + payloadLen;

    ByteBuf headers = net.alloc().buffer(IP6_HEADER_LEN + tcpHdrLen,
                                         IP6_HEADER_LEN + tcpHdrLen);
    byte[] src16 = srcAddr.getAddress();   // 16 字节
    byte[] dst16 = dstAddr.getAddress();   // 16 字节

    // === IPv6 固定头（40 字节）===
    // Byte 0-3: Version(6) + Traffic Class(0) + Flow Label(0)
    headers.writeInt(0x60000000);
    headers.writeShort(tcpSegLen);                   // Payload Length
    headers.writeByte(IP_PROTO_TCP);                 // Next Header = TCP
    headers.writeByte(IP6_DEFAULT_HOP_LIMIT);        // Hop Limit
    headers.writeBytes(src16);                       // Source Address
    headers.writeBytes(dst16);                       // Destination Address

    // === TCP 头（与 sendRaw4 相同，偏移从 IP6_HEADER_LEN 起）===
    // ... 写 srcPort / dstPort / seq / ack / flags / window / checksum-placeholder / urgPtr / opts

    // === TCP 校验和（IPv6 伪头部）===
    // 伪头部：src(16B) + dst(16B) + tcpLen(4B) + zeros(3B) + nextHeader(1B)
    long sum = 0;
    for (int i = 0; i < 16; i += 2)
        sum += ((src16[i] & 0xFF) << 8) | (src16[i+1] & 0xFF);
    for (int i = 0; i < 16; i += 2)
        sum += ((dst16[i] & 0xFF) << 8) | (dst16[i+1] & 0xFF);
    sum += (tcpSegLen >> 16) & 0xFFFF;
    sum += tcpSegLen & 0xFFFF;
    sum += IP_PROTO_TCP & 0xFF;                      // zeros(3B) + nextHeader 合并
    sum = checksumBuf(sum, headers, IP6_HEADER_LEN, tcpHdrLen);
    if (skb.rawPayload() != null)
        sum = checksumBuf(sum, skb.rawPayload(), skb.rawPayload().readerIndex(), payloadLen);
    headers.setShort(IP6_HEADER_LEN + TCP_CHECKSUM_OFFSET, (int)(~foldChecksum(sum) & 0xFFFF));
    // 注意：IPv6 无 IP header checksum，跳过步骤 6

    // === 组装发送 ===
    CompositeByteBuf packet = net.alloc().compositeBuffer(skb.rawPayload() != null ? 2 : 1);
    packet.addComponent(true, headers);
    if (skb.rawPayload() != null)
        packet.addComponent(true, skb.rawPayload().retainedSlice());
    net.writeAndFlush(packet);
}
```

---

##### C. `IpPacketHandler` 分派

工厂方法 `IpPacketBuf.retainedWrap()` 按版本创建子类后，`IpPacketHandler` 无需修改，`accept()` 只需检查 `pkt.nextProto() == ipProtocol` 即可同时覆盖 IPv4 和 IPv6。

`Tcp4DemultiplexHandler` 处理 `Ip4PacketBuf`，新增 `Tcp6DemultiplexHandler` 处理 `Ip6PacketBuf`，两者共用相同的 `TcpDemultiplexer` 核心逻辑（`TcpBuffer.srcAddr/dstAddr` 已是 `InetAddress`，天然支持两种版本）。

---

##### D. 实施顺序补充

在原 Step 1（新增 `IpPacketBuf`）中拆分为：

```
Step 1a  抽象 IpPacketBuf 基类（含 TCP 字段访问器）
Step 1b  Ip4PacketBuf 子类（迁移原 IPv4 实现）
Step 1c  Ip6PacketBuf 子类（新增 IPv6 支持）
```

IPv6 发送（`sendRaw6`）可在 Step 10 完成 IPv4 路径后作为独立子步骤追加。

---

## Part 2 — 其他优化项（原 design.md 内容）

> 以下为 2026-03-20 梳理的其他优化点，与 Part 1 独立，可并行推进。

---

### 2.1 现状与 FUTURE.md 对照

| 特性 | 当前状态 | 优化优先级 |
|------|---------|----------|
| 状态机（三次握手/四次挥手） | 部分实现 | P1 |
| 滑动窗口 | 未实现 | P1 |
| 时间戳选项（PAWS） | 未实现 | P2 |
| 选择性确认（SACK） | 未实现 | P2 |
| 慢启动 / 拥塞避免 | 未实现 | P3 |
| 快速重传 / 快速恢复 | 未实现 | P3 |
| 连接建立定时器 | 未实现 | P2 |
| ByteBuf 内存安全 | `TcpBuffer.rawPayload` 已移除，当前无 ByteBuf 持有；迁移时需重新引入并明确所有权 | P1 |
| Timer 资源管理 | 泄漏风险 | P0 |
| 关键字段并发保护 | 不足 | P1 |

---

### 2.2 [P1] ByteBuf 生命周期管理（Part 1 迁移时引入）

`TcpBuffer.rawPayload` 字段当前已移除，此问题暂不存在。**Part 1 Step 3 重新向 `TcpBuffer` 引入 `rawPayload` 时**，需同步落实以下约定：

- `TcpBuffer` 持有 `rawPayload` 唯一所有权（调用方 retain 后移交）
- 连接关闭时统一清空两个队列：
```
close():
  sk_write_queue.forEach(TcpBuffer::release)
  tcp_rtx_queue.forEach(TcpBuffer::release)
```

---

### 2.3 [P0] Timer 资源泄漏

`TcpSock` 三个 timer 保存 `Runnable`，`ScheduledFuture` 未持有，无法在连接关闭时取消。

**方案**：将字段替换为 `ScheduledFuture<?>`，关闭时显式 cancel；timer 回调首行检查 `sk_state == CLOSED`。

---

### 2.4 [P1] 滑动窗口实现

`FUTURE.md` 标注未实现（N）。发送前检查 `snd_nxt - snd_una < snd_wnd`，收到更大窗口 ACK 时若写队列非空立即触发发送。

---

### 2.5 [P1] 关键字段并发保护

`inet_connection_sock.pending` 的 `volatile int` + 位操作（`|=`/`&=~`）非原子，改用 `AtomicInteger`。所有 TCP 状态读写约束在绑定的 EventLoop 线程内执行；timer 回调通过 `eventLoop.execute(...)` 切换线程。

---

### 2.6 [P2] TCP 选项协商完整化

| 选项 | 当前 | 目标 |
|------|------|------|
| MSS | 部分 | SYN 阶段协商，发送时约束分段大小 |
| 窗口缩放 | ? | SYN 协商 `wscale`，发送/接收时移位 |
| 时间戳 | N | 携带 `TSval/TSecr`，用于 PAWS 和 RTT 估算 |
| SACK | N | 协商 + 接收乱序时构建 SACK block |

（Part 1 完成后，`TcpOptionCodec` 已就绪，直接在此基础上扩展）

---

### 2.7 [P2] 连接建立定时器

进入 `SYN_SENT`/`SYN_RECEIVED` 时启动定时器，超时重传 SYN-ACK（上限 `ipv4_tcp_synack_retries`），达上限后关闭连接。

---

### 2.8 [P3] 拥塞控制

慢启动 → 拥塞避免 → 快速重传 → 快速恢复，后续单独设计文档。

---

## 参考

- [RFC 793](https://www.rfc-editor.org/rfc/rfc793) — TCP 规范
- [RFC 1323](https://www.rfc-editor.org/rfc/rfc1323) — 时间戳 / 窗口缩放
- [RFC 2018](https://www.rfc-editor.org/rfc/rfc2018) — SACK
- [RFC 5681](https://www.rfc-editor.org/rfc/rfc5681) — 拥塞控制
- [RFC 7323](https://www.rfc-editor.org/rfc/rfc7323) — PAWS
- `pangolin-routing/src/main/resources/FUTURE.md` — 功能支持状态表
