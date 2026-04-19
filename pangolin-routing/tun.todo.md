# TUN Adapter 待办清单

针对 `com.github.pangolin.routing.acceptor.tun.adapter` 跨平台实现（Darwin / Linux / Windows）的剩余待办。已修复条目（12 个 BUG + OPT-1/2/3/5/8）从清单移除，修复细节保留在 git 历史中。

> **当前状态**（截至 2026-04-19）
> - 剩余优化：3 项（OPT-4 / OPT-6 / OPT-7）
> - 待确认：3 项，需真机或单测辅助
> - v2 待办：1 项强语义改造，独立版本推进

---

## 一、剩余优化

### OPT-7【High】`TunAdapter` 未实现 `AutoCloseable`
- **位置**: `TunAdapter.java`
- **收益**: 支持 try-with-resources；对上层调用体验直接可见。
- **方案**: 实现 `AutoCloseable`，`close()` 委托 `destroy()`；注意 `destroy()` 抛 `IOException`，`AutoCloseable#close()` 签名兼容。
- **改动量**: < 10 行。

### OPT-4【Medium】Darwin `write0(ByteBuffer[])` 每次新建 4 字节 AF buffer
- **位置**: `DarwinTunAdapter.java:119-126`
- **现象**: 每次写包都 `ByteBuffer.allocateDirect(4)` 写入 address family，属于热路径上的 GC 压力与分配开销。
- **方案**:
  - 按 `ipVersion` 静态缓存 2 个只读 direct `ByteBuffer`（或 `Memory`）分别对应 `AF_INET` / `AF_INET6`；
  - 或将 AF 前缀合入 iovec 外层共享缓冲，避免 per-write 分配。
- **风险**: 只读缓冲须确保调用方不会写入，`ByteBuffer#asReadOnlyBuffer` 可规避；`duplicate()` 仅共享 backing store，不会互相污染 position/limit。

### OPT-6【Low】用 `assert` 做边界校验
- **位置**: `NetUtils2.java:100, :121` 等
- **现象**: JVM 生产环境默认不开启 `-ea`，断言被忽略，错误输入将继续传播。
- **方案**: 换成 `Preconditions.checkArgument` 或 `IllegalArgumentException`。
- **改动量**: 几处等价替换，可与后续重构一起顺手清理。

---

## 二、待确认（需要真机或单测）

### CONF-1 `KernControl.sockaddr_ctl` FieldOrder 对齐
- **对齐基准**: `<sys/kern_control.h>`，结构应含 `sc_reserved[5]`，`sizeof == 32`。
- **验证方式**: 新增单测断言 `new KernControl.sockaddr_ctl(...).size() == 32`。
- **后果**: 若对齐错误，`connect(fd, sockaddr_ctl, sc_len)` 会 `EINVAL`，utun 创建直接失败；当前没有故障观测。

### CONF-2 Darwin `socket(AF_ROUTE, SOCK_RAW, AF_UNSPEC)` 第三参语义
- **位置**: `DarwinNetworkRoutingTable.route0()` 的 route socket 打开处。
- **标准写法**: macOS 通常第三参写 `0`；当前写 `AF_UNSPEC` 能工作但语义不标准。
- **验证方式**: 真机切换到 `0` 后跑完整路由 add/delete/dump 用例，对比行为一致。

### CONF-3 Windows 多 binding 的 IPv4/IPv6 混用行为
- **背景**: `WindowsTunAdapter.open` 注释掉了 IPv4 走 `AddIPAddress` 的 fast-path，统一改走 LUID + `CreateUnicastIpAddressEntry`。
- **待验证**:
  1. **metric 一致性** —— `AddIPAddress` 与 `CreateUnicastIpAddressEntry` 生成的 on-link / 子网路由 metric 路径不同，tun 路由优先级可能偏离预期。
  2. **weak-host 下的前缀隔离** —— 混合 v4 + v6 bindings 时各前缀不会互相"吃掉"对方报文。
  3. **自动路由对齐** —— `WindowsTunAdapter` 头注释描述的 `198.18.0.0/24 ...` 自动路由行为是否依然按原 metric / 前缀出现。
- **验证方式**: 真机分别用两种 API 添加 v4 地址，对比 `route print -4` 产物；再混合 v4 + v6 bindings 抓包确认前缀隔离。
- **若有差异**: 要么保留老的 v4 fast-path，要么在新 API 调用前显式 `SetIpInterfaceEntry` 调 metric。

---

## 三、v2 待办（强语义改造，独立版本推进）

本章承载"非 best-effort"的 destroy/read 协同方案。v1（当前仓库状态）通过文档化接受 best-effort 语义；v2 将以独立版本落地显式唤醒机制，彻底消除 TOCTOU 与 read 阻塞无法主动终止的问题。两者可以共存：v2 作为独立 `TunAdapter` 子类或开关位推进，不回滚 v1 的文档承诺。

### v2-1【核心】显式唤醒 close：pipe / eventfd / Event

- **目标**: `destroy()` 能主动唤醒阻塞在 `read0()` 的线程，并在唤醒后安全回收 fd/handle，无需依赖 OS 层 `EBADF` / `ERROR_HANDLE_EOF`。
- **方案概要**: 每个 `TunAdapter` 实例在 open 阶段额外创建一个"关闭信号"通道；`read0` 不直接阻塞在 tun fd，而是阻塞在 "tun fd ∪ 关闭信号" 的 readiness 事件上；`destroy0` 先触发关闭信号、等待 read 线程退出、再关闭 tun fd。
- **分平台实现要点**:
  - **Linux**:
    - 新增 `LibC#eventfd(int initval, int flags)`；`read0` 改为 `poll(pollfd[2])`（tunFd + eventfd）再发起 `read(2)`；`destroy0` 先 `eventfd_write(shutdownFd, 1)` 再 `close(tunFd)`、`close(shutdownFd)`。
    - 需定义 `pollfd` JNA Structure；或直接用 `select(2) + fd_set`。
  - **Darwin**:
    - 不支持 eventfd，用 `pipe(int[2])` 代替；其余同 Linux。
    - 或引入 `kqueue` + `EV_SET(EVFILT_READ, ...)` 更原生。
  - **Windows**:
    - 复用 `WintunGetReadWaitEvent` + 新建 `CreateEventW(null, TRUE, FALSE, null)` 作为 shutdown event；
    - `read0` 的 `WaitForSingleObject` 改为 `WaitForMultipleObjects(2, handles, FALSE, INFINITE)`；
    - `destroy0` 先 `SetEvent(shutdownEvent)` 再 `WintunEndSession`/`CloseAdapter`、最后 `CloseHandle(shutdownEvent)`。
- **TunAdapter 基类调整**:
  - 引入 `protected void wakeup0()` 钩子，`destroy()` 变为 `wakeup0(); joinReaderIfNeeded(); destroy0();`；
  - 或新增 `ClosedByInterruptException`，`read0` 在命中 shutdown 时抛出，便于上层区分主动关闭与 IO 错误。
- **改动量估算**: 250~350 行，覆盖 6~7 个文件：
  - `unix/jna/LibC.java`（+`pipe`/`eventfd`/`poll`/`pollfd`，约 +40~60 行）
  - `linux/jna/` 下新增 eventfd 相关常量（可选）
  - `LinuxTunAdapter` / `DarwinTunAdapter` 各 +30~50 行
  - `WindowsTunAdapter` 约 +20~30 行（JNA 可复用 `com.sun.jna.platform.win32.Kernel32`）
  - `TunAdapter` 基类 +10 行
- **推进顺序建议**: Windows（最简，Wintun 原生支持 event wait）→ Linux（eventfd 比 pipe 省一个 fd）→ Darwin（复用 Linux 的 pipe/poll 绑定）。
- **验收**: 三平台各跑冒烟——正常 read/write + destroy 时阻塞在 read 的线程必须能在 50ms 内退出且不泄漏 fd。
- **风险与注意**:
  1. `poll` / `pollfd` 的 JNA 绑定坑较多（数组按结构传参），需要单独单测验证；
  2. fd 生命周期必须重排：`destroy` 里先发唤醒、再 join read 线程、最后 close 两个 fd，避免 fd 复用竞态；
  3. 若未来要支持"关闭后拒绝新的 read/write"强语义，可把 `closed` 置位、唤醒、join、close 这四步合并进 `destroy()` 的关键区。

### v2-2【可选】write 路径对称唤醒

- **目标**: `write0` 在极端场景（满载 tun 队列）也能被 destroy 中断。
- **结论**: 一般场景下 tun 设备 write 几乎即时返回，阻塞概率极低，可作为 v2-1 的后续延伸，不阻塞 v2-1 上线。

---

## 四、推进顺序建议

1. **OPT-7** —— 对上层收益最直接，成本 < 10 行。
2. **OPT-4** —— 热路径微优化，需要简单压测验证。
3. **OPT-6** —— 低优先级，随其他重构顺手清理。
4. **CONF-1 / CONF-2 / CONF-3** —— 需要真机或单测，建议作为回归测试的一部分。
5. **v2-1** —— 独立版本推进，优先级由上层是否遇到"关闭无法唤醒"的实际问题决定。
6. **v2-2** —— 跟随 v2-1 之后。
