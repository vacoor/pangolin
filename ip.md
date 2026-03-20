# IpPacketBuf 传输层分离设计

## 现状问题

`IpPacketBuf` 当前将三层职责混杂在一个类中：

- **IP 层**：`version`, `ipHeaderLen`, `nextProto`, `hopLimit`, `srcAddr`, `dstAddr`
- **TCP 层**：`tcpSrcPort`, `tcpSeq`, `isSyn`, `tcpOptionsSlice`, `tcpPayloadSlice`, ...
- **UDP 层**：`udpSrcPort`, `udpDstPort`, `udpPayloadSlice`, ...

随着协议增加（ICMP、GRE 等），该类会无限膨胀，且对 TCP handler 暴露了 UDP 方法，类型不安全。

---

## 设计目标

1. `IpPacketBuf` 只保留 IP 层字段
2. TCP handler 接收 `TcpPacketBuf`，UDP handler 接收 `UdpPacketBuf`，编译期类型安全
3. 零拷贝特性保持不变（全部使用 `buf.getXxx(absoluteOffset)`）
4. 工厂方法 `retainedWrap` 根据 IP version + nextProto 自动创建正确子类
5. 对现有代码改动尽量局限于参数类型替换

---

## 类层次结构

```
IpPacketBuf  (abstract)                      — IP 头字段
├── Ip4PacketBuf                             — IPv4 专属字段 (tos, ipId, ipFlags)
└── Ip6PacketBuf                             — IPv6 专属字段 (flowLabel, etc.)

TcpPacketBuf (abstract) extends IpPacketBuf  — TCP 头字段
├── Tcp4PacketBuf extends TcpPacketBuf       — TCP/IPv4，含 IPv4 专属字段
└── Tcp6PacketBuf extends TcpPacketBuf       — TCP/IPv6，含 IPv6 专属字段

UdpPacketBuf (abstract) extends IpPacketBuf  — UDP 头字段
├── Udp4PacketBuf extends UdpPacketBuf       — UDP/IPv4
└── Udp6PacketBuf extends UdpPacketBuf       — UDP/IPv6
```

`Ip4PacketBuf` / `Ip6PacketBuf` 保留用于协议未知的裸 IP 包（如路由转发场景）。

---

## 各类职责

### `IpPacketBuf` (abstract) — 只保留
```
version(), ipHeaderLen(), nextProto(), hopLimit()
srcAddrBytes(), dstAddrBytes(), srcAddr(), dstAddr()
buf(), release(), resolvedDstAddr()
PROTO_TCP, PROTO_UDP 常量
```
**移除**：所有 TCP_FLAG_xxx 常量、所有 tcp* / udp* 方法

### `TcpPacketBuf` (abstract)
```
tcpBase()  →  readerIndex + ipHeaderLen()
tcpSrcPort(), tcpDstPort()
tcpSeq(), tcpAckNum()
tcpDataOffset(), tcpFlags(), tcpWindow()
isFin/isSyn/isRst/isPsh/isAck/isUrg/isTcp()
tcpOptionsSlice(), tcpPayloadSlice(), tcpPayloadLength()
TCP_FLAG_xxx 常量
```

### `Tcp4PacketBuf` (final)
```
继承 TcpPacketBuf 所有 TCP 方法
实现 ipHeaderLen() → (buf.getByte(base) & 0xF) * 4
tos(), ipId(), ipFlags()         ← IPv4 专属
srcAddr4(), dstAddr4()           ← 强类型返回 Inet4Address
```

### `UdpPacketBuf` (abstract)
```
udpBase()  →  readerIndex + ipHeaderLen()
udpSrcPort(), udpDstPort()
udpLength(), udpPayloadLength(), udpPayloadSlice()
```

---

## IPv4 字段重复问题

`Ip4PacketBuf` 和 `Tcp4PacketBuf` 都需要 IPv4 专属字段（`tos`, `ipId` 等）。
Java 不支持多重继承，通过 **包级静态工具类** 共享实现：

```java
// package-private
final class Ip4Fields {
    static byte       tos(ByteBuf buf, int base) { return buf.getByte(base + 1); }
    static short      ipId(ByteBuf buf, int base) { return buf.getShort(base + 4); }
    static int    ipHeaderLen(ByteBuf buf, int base) { return (buf.getByte(base) & 0xF) * 4; }
    static short  ipFlags(ByteBuf buf, int base)  { return buf.getShort(base + 6); }
    static int    hopLimit(ByteBuf buf, int base)  { return buf.getUnsignedByte(base + 8); }
}
```

`Ip4PacketBuf`、`Tcp4PacketBuf`、`Udp4PacketBuf` 均委托给 `Ip4Fields`，无代码重复。

---

## 工厂方法

`IpPacketBuf.retainedWrap(ByteBuf)` 根据 version + nextProto 创建最具体的子类：

```
version=4, proto=TCP(6)   → Tcp4PacketBuf
version=4, proto=UDP(17)  → Udp4PacketBuf
version=4, proto=其他      → Ip4PacketBuf
version=6, proto=TCP(6)   → Tcp6PacketBuf
version=6, proto=UDP(17)  → Udp6PacketBuf
version=6, proto=其他      → Ip6PacketBuf
```

对调用方完全透明，`IpPacketCodec.decode()` 不需要修改。

---

## 对现有代码的影响

| 文件 | 改动 |
|---|---|
| `IpPacketHandler<P>` | 泛型化：`abstract void channelRead0(ctx, P pkt)` |
| `TcpDemultiplexHandler` | 泛型参数 `<TcpPacketBuf>`，`channelRead0` 接收 `TcpPacketBuf` |
| `TcpDemultiplexer.tcp_rcv` | 参数 `IpPacketBuf` → `TcpPacketBuf` |
| `TcpInput` / `TcpOutput` / `TcpHandshaker` | 所有 `IpPacketBuf pkt` → `TcpPacketBuf pkt` |
| `Tcp4Demultiplexer` | 同上；`Tcp4PacketBuf` 替代 `IpPacketBuf` 处理 IPv4 专属字段 |
| `Udp4PacketHandler` | `channelRead0` 接收 `UdpPacketBuf` |
| `IpPacketBuf` | 删除全部 tcp*/udp* 方法及 TCP_FLAG_xxx 常量 |
| `IpPacketCodec` | 无需改动（`retainedWrap` 自动返回正确子类） |

---

## 迁移步骤

1. 新增 `Ip4Fields` 静态工具类（package-private）
2. 新增 `TcpPacketBuf` 抽象类，从 `IpPacketBuf` 提取 TCP 方法
3. 新增 `Tcp4PacketBuf`（final），继承 `TcpPacketBuf` + 委托 `Ip4Fields`
4. 新增 `Tcp6PacketBuf`（final）
5. 新增 `UdpPacketBuf` 抽象类，从 `IpPacketBuf` 提取 UDP 方法
6. 新增 `Udp4PacketBuf`（final）
7. 更新 `IpPacketBuf.retainedWrap` 工厂方法
8. 清理 `IpPacketBuf`：删除 TCP/UDP 方法
9. `IpPacketHandler` 泛型化
10. 批量替换各 handler 的参数类型

步骤 1-8 可独立完成并保持编译通过，步骤 9-10 作为最后一步整体切换。
