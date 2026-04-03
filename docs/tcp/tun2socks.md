# Pangolin vs tun2socks 功能对比

> 更新日期：2026-04-04

## 背景

- **Pangolin**：基于 Netty + Java 的 TUN 层透明代理，在用户态实现了完整的 Linux TCP 状态机，通过 TUN 网卡拦截系统流量并代理转发。
- **tun2socks**：Go 语言编写的开源透明代理工具（https://github.com/xjasonlyu/tun2socks），底层使用 Google gVisor 用户态协议栈，将 TUN 流量转换为 SOCKS5/HTTP/Shadowsocks 等代理请求。

---

## 一、实现架构

### Pangolin

- **语言 / 运行时**：Java 17+，运行于 JVM
- **网络框架**：Netty（基于事件驱动的异步 I/O 框架）
- **协议栈**：完全自研，对照 Linux 内核 `net/ipv4/tcp_input.c`、`tcp_output.c` 逐函数移植
- **TUN 驱动**：
  - Linux：`/dev/net/tun`，通过 JNA 调用
  - macOS：utun 内核接口，通过 JNA 调用
  - Windows：[Wintun](https://www.wintun.net/)，通过 JNA 调用
- **并发模型**：Netty EventLoop，单线程处理每个 Channel，无锁设计
- **DNS**：内置 Fake-DNS 引擎，将域名映射到虚拟 IP（198.18.0.0/15），支持 DoH

### tun2socks

- **语言 / 运行时**：Go，静态编译单二进制文件
- **网络框架**：标准库 + 自定义 goroutine pipeline
- **协议栈**：[gVisor](https://gvisor.dev/)（Google 用户态 TCP/IP 实现），直接通过 `gvisor.dev/gvisor/pkg/tcpip` 接入
- **TUN 驱动**：[go-tun2socks/water](https://github.com/songgao/water) / [wireguard-go/tun](https://github.com/WireGuard/wireguard-go)，跨平台封装
- **并发模型**：goroutine-per-connection，依赖 Go runtime 调度
- **DNS**：依赖外部工具（如 Clash、sing-box）或系统 DNS，自身不内置 Fake-DNS

---

## 二、协议支持

| 协议 | Pangolin | tun2socks |
|------|----------|-----------|
| TCP（IPv4） | 完整支持（自研状态机） | 完整支持（gVisor） |
| TCP（IPv6） | 部分支持（Tcp6PacketBuf 存在，状态机待验证） | 完整支持 |
| UDP（IPv4） | 基础转发（Udp4PacketHandler），当前仅 Fake-DNS 拦截 | 完整支持 |
| UDP（IPv6） | 待完善 | 完整支持 |
| ICMP | 不支持 | 可选支持（ICMPHandler） |
| DNS（Fake-DNS） | 原生内置，支持 DoH | 不内置，需外部配合 |

---

## 三、TCP 实现完整度

### Pangolin（对照 Linux 内核逐函数移植）

| 特性 | 状态 | 备注 |
|------|------|------|
| 完整 TCP 状态机（11 态） | 已实现 | `TcpState`，含 TIME_WAIT、CLOSING 等 |
| 三次握手 / 四次挥手 | 已实现 | `TcpHandshaker`，含 SYN Cookie 框架 |
| 窗口缩放（Window Scaling，RFC 7323） | 已实现 | `SysctlOptions.ipv4_sysctl_tcp_window_scaling = true` |
| TCP 时间戳（Timestamps，RFC 7323） | 已实现 | `sysctl_tcp_timestamps = true`，含 tsoffset 混淆 |
| PAWS（Protection Against Wrapped Seq） | 已实现 | `TCP_PAWS_24DAYS`、`TCP_PAWS_MSL`、`TCP_PAWS_WINDOW` |
| 乱序重组（OFO Queue） | 已实现 | `out_of_order_queue`（TreeMap），`sysctl_tcp_ofo_enabled = true` |
| Nagle 算法 | 已实现（默认关闭） | `TCP_NAGLE_OFF` |
| Keep-Alive | 已实现 | `TcpTimer`，参数对应内核 `sysctl` |
| TIME_WAIT 复用（tw_reuse） | 框架存在 | `ipv4_sysctl_tcp_tw_reuse = 0`（默认关闭） |
| RTO 计算（RFC 6298） | 已实现 | `TCP_TIMEOUT_INIT = 1s`，`srtt_us`、`rttvar_us` |
| 拥塞控制（cwnd / ssthresh） | ✅ 已实现（Reno） | RFC 5681 慢启动、拥塞避免、快速重传、快速恢复（NewReno/RFC 6582）、RTO 丢失恢复均已实现；Cubic/BBR 未实现 |
| 快速重传 / 快速恢复 | ✅ 已实现 | `tcp_fastretrans_alert()` + `tcp_enter_fast_recovery()` + NewReno 部分 ACK 处理（RFC 5681 + RFC 6582） |
| MSS 协商 | 已实现 | `TCP_MSS_DEFAULT = 536`，`TCP_MIN_MSS = 88` |
| 紧急指针（URG） | 已弃用 | `snd_up` 标注 `@Deprecated` |
| Challenge ACK（RFC 5961） | 已实现 | `tcp_challenge_ack_limit` 限速 |
| out-of-window ACK 限速 | 已实现 | `__tcp_oow_rate_limited` |

### tun2socks（基于 gVisor）

| 特性 | 状态 | 备注 |
|------|------|------|
| 完整 TCP 状态机 | 已实现 | gVisor 成熟实现 |
| 窗口缩放 | 已实现 | gVisor 默认支持 |
| TCP 时间戳 | 已实现 | gVisor 支持 |
| PAWS | 已实现 | gVisor 支持 |
| 乱序重组 | 已实现 | gVisor 完整实现 |
| 拥塞控制 | 已实现（Cubic） | gVisor 内置 Cubic，可扩展 |
| 快速重传 / SACK | 已实现 | gVisor 支持 |
| Keep-Alive | 已实现 | gVisor 支持 |

---

## 四、上游代理协议支持

| 代理协议 | Pangolin | tun2socks |
|----------|----------|-----------|
| SOCKS5 | 支持 | 支持 |
| SOCKS4 | 支持（含 Mixin 接入） | 不支持 |
| HTTP CONNECT | 支持 | 支持 |
| Shadowsocks（SS） | 支持（AEAD + Stream） | 支持 |
| Trojan | 支持 | 支持 |
| SSH Tunnel | 支持 | 不支持 |
| WebSocket 代理 | 支持 | 不支持 |
| WireGuard | 不支持 | 支持（内置） |
| 代理链（Chain） | 支持（UpstreamChainFactory） | 不支持 |
| AI 负载均衡 / 自适应选路 | 支持（RouteQuality / 自研） | 不支持 |

---

## 五、性能特征

| 维度 | Pangolin | tun2socks |
|------|----------|-----------|
| 启动时间 | JVM 启动较慢（秒级） | 毫秒级（Go 静态二进制） |
| 内存占用 | JVM 基础开销（100~300 MB+） | 低（10~50 MB） |
| 吞吐量 | 高（Netty 零拷贝、DirectBuffer） | 高（gVisor 优化较成熟） |
| 延迟 | JVM 早期 JIT 暖机阶段略高，稳定后接近 | 低，Go runtime 调度开销小 |
| GC 停顿 | 存在 JVM GC 停顿（可通过 ZGC/G1 优化） | 无 GC（Go 的 GC 停顿通常 < 1ms） |
| 拥塞控制成熟度 | Reno 已完整实现（Cubic/BBR 待实现） | 成熟（gVisor Cubic） |
| CPU 利用率 | EventLoop 单线程，CPU 亲和性好 | goroutine 多核可并行 |

---

## 六、部署方式

| 维度 | Pangolin | tun2socks |
|------|----------|-----------|
| 分发形式 | JAR + JRE，或 Docker 镜像 | 单静态二进制（无依赖） |
| 依赖 | Java 17+ 运行时，Wintun（Windows） | 无额外运行时依赖 |
| 配置方式 | 代码配置 / Spring Bean / 自定义 DSL | 命令行参数 / YAML（配合 Clash） |
| 平台支持 | Linux、macOS、Windows（均有适配层） | Linux、macOS、Windows、FreeBSD、OpenBSD |
| 网关模式 | 支持（通过路由表控制） | 支持（Gateway Mode） |
| 容器化 | 支持 | 支持（官方 Docker 镜像） |
| 嵌入式集成 | 容易（作为 Java 库嵌入其他服务） | 难（独立进程，需 IPC） |

---

## 七、扩展性

| 维度 | Pangolin | tun2socks |
|------|----------|-----------|
| 新增代理协议 | 实现 `Upstream` / `UpstreamFactory` SPI 即可 | 实现 `proxy.Proxy` 接口 |
| 自定义路由规则 | 内置规则引擎（Subnet、域名、PAC 等） | 依赖外部（Clash / sing-box） |
| 监控 / 可观测性 | 依赖 JVM 生态（Micrometer 等） | 内置 REST API（统计接口） |
| TCP 协议栈定制 | 源码级修改（Java 可读性高） | 需修改 gVisor 依赖（复杂） |
| 业务逻辑集成 | 高（JVM 生态，可集成 AI、数据库等） | 低（轻量工具定位） |
| 拥塞控制算法替换 | Reno 已实现；Cubic/BBR 框架预留接口，可扩展 | 受限于 gVisor 支持的算法 |

---

## 八、综合对比表

| 维度 | Pangolin | tun2socks |
|------|----------|-----------|
| 协议栈实现 | 自研（Linux 内核移植） | gVisor（Google 用户态协议栈） |
| TCP 精细度 | 极高（逐函数对照内核） | 高（gVisor 成熟实现） |
| 拥塞控制 | Reno 已实现，Cubic/BBR 待实现 | Cubic（成熟） |
| UDP 支持 | 基础（当前仅 Fake-DNS） | 完整 |
| ICMP 支持 | 无 | 可选 |
| 代理协议丰富度 | 高（含 SSH、WS、链式） | 中（SOCKS5/HTTP/SS/WG） |
| 自适应选路 | 支持 | 不支持 |
| 性能开销 | JVM 有基础开销 | 低 |
| 部署便捷性 | 中（需要 JRE） | 高（单二进制） |
| 可扩展 / 可集成性 | 高（JVM 生态） | 低（独立工具） |
| 维护团队 | 私有项目 | 活跃开源社区 |

---

## 九、结论

### Pangolin 的优势

1. **TCP 实现精度极高**：逐函数对照 Linux 内核移植，PAWS、时间戳、TIME_WAIT、乱序重组等机制均已实现，与真实系统行为高度一致，适合需要精确控制 TCP 行为的场景。
2. **代理协议更丰富**：支持 SSH Tunnel、WebSocket 代理、代理链串联，以及 Shadowsocks、Trojan 等，覆盖更复杂的网络环境。
3. **内置 AI 自适应选路**：RouteQuality 框架可根据实测质量动态切换上游，这是 tun2socks 不具备的能力。
4. **可集成性强**：作为 Java 库可嵌入大型服务系统，复用 JVM 生态（Spring、Micrometer、各种中间件客户端）。
5. **Fake-DNS 原生集成**：域名到虚拟 IP 的映射在协议栈内部完成，无需外部 DNS 代理进程。

### Pangolin 的不足

1. **Cubic/BBR 拥塞控制未实现**：RFC 5681 Reno（慢启动、拥塞避免、快速重传/恢复）已完整实现；Cubic 和 BBR 算法框架预留但尚未实现，高带宽长延迟（BDP 大）网络下吞吐上限低于 Cubic。
2. **UDP 支持有限**：当前 `Udp4PacketHandler` 仅处理 Fake-DNS 的 UDP 查询，通用 UDP 转发尚未实现。
3. **JVM 启动与内存开销**：对于嵌入式/边缘场景，JVM 的内存基线（100 MB+）和启动时间（秒级）是劣势。
4. **平台适配维护成本**：Wintun（Windows）、utun（macOS）、tun（Linux）需各自维护 JNA 绑定。

### tun2socks 的优势

1. **开箱即用、部署极简**：单静态二进制，无运行时依赖，适合快速落地和边缘部署。
2. **gVisor 协议栈成熟**：拥塞控制（Cubic）、SACK、IPv6 等均已完整实现，经过 Google 大规模生产验证。
3. **Go 语言低开销**：内存占用低、GC 停顿极小、启动快，适合资源受限场景。
4. **活跃开源社区**：与 Clash / sing-box 生态紧密集成，文档和周边工具丰富。

### tun2socks 的不足

1. **可定制性弱**：不内置路由引擎、Fake-DNS、代理链，需要配合外部工具。
2. **代理协议受限**：不支持 SSH Tunnel、WebSocket 代理、代理链等高级组合。
3. **TCP 行为不可控**：gVisor 的 TCP 实现细节封装于依赖库，难以针对特殊场景调优。

### 选型建议

| 场景 | 推荐 |
|------|------|
| 需要精确 TCP 行为控制、深度定制 | Pangolin |
| 需要嵌入 Java 业务系统 | Pangolin |
| 需要 AI 自适应选路 / 代理链 | Pangolin |
| 快速部署、资源受限、边缘节点 | tun2socks |
| 需要完整 UDP / ICMP 支持 | tun2socks |
| 与 Clash / sing-box 生态集成 | tun2socks |
| 个人翻墙 / 代理工具 | tun2socks |
