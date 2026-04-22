# TUN Adapter 待办清单

针对 `com.github.pangolin.routing.acceptor.tun.adapter` 跨平台实现（Darwin / Linux / Windows）的剩余待办。已修复条目（12 个 BUG + OPT-1/2/3/5/8）从清单移除，修复细节保留在 git 历史中。

> **当前状态**（截至 2026-04-22）
> - 剩余优化：3 项（OPT-4 / OPT-6 / OPT-7）
> - 待确认：3 项，需真机或单测辅助
> - v2 改造：已落地三平台 adapter + netty channel，剩单测/验收未跑

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

## 三、v2 改造（已落地）

目标：把 tun fd 改造成**可被外部唤醒的 blocking channel**，让上层在 tun 空闲时也能在有界时间内优雅关闭，不再依赖 OS 层 `EBADF` / `ERROR_HANDLE_EOF` 的 best-effort。

v1 和 v2 **并存**在同一包下，互不继承、互不引用；调用方按需选择。

### v2-1【已落地】显式唤醒 close

#### 最终落地的设计契约

1. **状态**：`TunAdapterV2` 用单个 `AtomicBoolean closed` 表征 open/closed 两态。原设计的三态 `OPEN/CLOSING/CLOSED` 在简化后被砍掉 —— 多出来的 CLOSING 中间态只有分段式 API 才用得上，单一入口 `close()` 下没价值。
2. **公共 API**：仅 4 个方法
   - `read()` / `write(ByteBuffer[])`：已 `close()` 时抛 `ClosedChannelException`；`read()` 被唤醒时抛 `ClosedByWakeupException`（`IOException` 子类）。
   - `wakeup()`：线程安全，让阻塞的 `read()` 立即返回并抛 `ClosedByWakeupException`；不释放 fd；已 `close()` 后为 no-op（**防 UAF**：fd 数字被 OS 回收后可能被其它资源重用，wakeup 到重用 fd 会静默破坏无关资源）。
   - `close()`：幂等；实现 `Closeable`。**契约**：调用方保证读线程已退出或从未启动。违反会 UAF。
   - `ClosedByWakeupException` 专门用来区分"被 wakeup 的正常退出"和"真 IO 错误"两条路径。
3. **标准使用模式（二选一）**：

   **用法 A — read 线程自释放（推荐）**
   ```java
   Thread reader = new Thread(() -> {
       try {
           while (true) handle(tun.read());
       } catch (ClosedByWakeupException e) {
           // 被 wakeup，优雅退出
       } catch (IOException e) {
           // 其他 IO 异常
       } finally {
           try { tun.close(); } catch (IOException ignore) {}
       }
   });
   reader.start();

   // 外部关闭：
   tun.wakeup();
   // 可选：reader.join(timeoutMs);
   ```
   read 线程的 finally 里 close 与 read 物理上不可能并发，UAF 被消除在源头。

   **用法 B — 外部编排（netty / 自控 reader）**
   ```java
   tun.wakeup();
   reader.join();           // netty 里替换成 eventLoop.awaitTermination()
   tun.close();
   ```
4. **读路径非阻塞化**：三平台 open 阶段对 tun fd 设 `O_NONBLOCK`；`read0` 统一为 "wait 多路复用 → try read → EAGAIN 回到 wait" 的循环。

#### 分平台实现

##### Linux — epoll + eventfd

`adapter/linux/jna/LibCEpoll.java`（新增 v2 专用 JNA 绑定，不动 v1 `unix/jna/LibC.java`）：
```java
int eventfd(int initval, int flags);
int epoll_create1(int flags);
int epoll_ctl(int epfd, int op, int fd, Pointer event);
int epoll_wait(int epfd, Pointer events, int maxEvents, int timeout);
int fcntl(int fd, int cmd, int arg);
```
常量：`EPOLL_CTL_ADD=1`、`EPOLLIN=0x001`、`EFD_NONBLOCK=04000`、`F_SETFL=4`、`O_NONBLOCK=04000`、`EAGAIN=11`、`EINTR=4`。

`epoll_event` 用 `Memory` 裸写 12 字节（x86_64 packed），避免 JNA `Structure` 的对齐差异：
```
offset 0: uint32_t events
offset 4: uint64_t data   // 只存 fd，u64 装 int 够用
```

`LinuxTunAdapterV2`（`adapter/linux/LinuxTunAdapterV2.java`）：
- open：`fcntl(tunFd, F_SETFL, O_NONBLOCK)` → `eventfd(0, EFD_NONBLOCK)` → `epoll_create1(0)` → `epoll_ctl(ADD)` 注册 tunFd、wakeupFd（用 `data.u64 = fd` 作为返回事件的区分）。
- `read0`：`epoll_wait` → 扫描返回事件里有 `wakeupFd` 就抛 `ClosedByWakeupException` → 否则 `read(tunFd)`，EAGAIN 重试。
- `wakeup0`：`write(wakeupFd, 8-byte 1L, 8)`（eventfd 语义，累加到内核计数器触发 EPOLLIN）。
- `destroy0`：close `epfd / wakeupFd / tunFd`。

##### Darwin — kqueue + EVFILT_USER

`adapter/darwin/jna/LibCKqueue.java`（新增）：
```java
int kqueue();
int kevent(int kq, Pointer changelist, int nchanges,
           Pointer eventlist, int nevents, Pointer timeout);
int fcntl(int fd, int cmd, int arg);
```
常量：
- `EVFILT_READ = -1`、`EVFILT_USER = -10`
- `EV_ADD = 0x0001`、`EV_CLEAR = 0x0020`
- `NOTE_TRIGGER = 0x01000000`
- `F_SETFL = 4`、Darwin `O_NONBLOCK = 0x0004`（与 Linux 的 `04000` 不同，子包独立定义）
- `EAGAIN = 35`、`EINTR = 4`（与 Linux 值不同）

`struct kevent` 32 字节、自然对齐（无 epoll 那种 packed 坑）：
```
offset 0  uintptr_t ident
offset 8  int16_t   filter
offset 10 uint16_t  flags
offset 12 uint32_t  fflags
offset 16 intptr_t  data
offset 24 void     *udata
```

`DarwinTunAdapterV2`（`adapter/darwin/DarwinTunAdapterV2.java`）：
- open：`fcntl(utun, F_SETFL, O_NONBLOCK)` → `kqueue()` → 一次 `kevent()` 注册两条 change：
  - `{ ident=tunFd, filter=EVFILT_READ, flags=EV_ADD|EV_CLEAR }`
  - `{ ident=WAKEUP_IDENT(=1), filter=EVFILT_USER, flags=EV_ADD|EV_CLEAR }`
- `read0`：`kevent(kq, null, 0, events, MAX_EVENTS, null)` 阻塞；命中 `EVFILT_USER + WAKEUP_IDENT` 抛 `ClosedByWakeupException`；否则 `read(utun)`，剥 4 字节 AF 头。
- `wakeup0`：一次 `kevent()` 发 `{ ident=WAKEUP_IDENT, filter=EVFILT_USER, flags=0, fflags=NOTE_TRIGGER }`，eventlist 传 null；立即返回。
- `destroy0`：`close(kq) → close(utun)`（kq 关闭自动清理已注册 filter）。

##### Windows — WaitForMultipleObjects + Event

Wintun 原生支持 event wait，JNA 复用 `com.sun.jna.platform.win32.Kernel32` 已有的 `CreateEvent / SetEvent / WaitForMultipleObjects / CloseHandle`。

`WindowsTunAdapterV2`（`adapter/windows/WindowsTunAdapterV2.java`）：
- open：`WintunOpen/CreateAdapter` → `WintunStartSession` → `Kernel32.CreateEvent(null, true /*manual-reset*/, false, null)` 得 `shutdownEvent`。
- `read0`：先 `WintunReceivePacket`，命中 `ERROR_NO_MORE_ITEMS` 则 `WaitForMultipleObjects(2, {readEvent, shutdownEvent}, false, INFINITE)`，返回 `WAIT_OBJECT_0 + 1` 抛 `ClosedByWakeupException`；否则回到 `WintunReceivePacket`。
- `wakeup0`：`Kernel32.SetEvent(shutdownEvent)`。
- `destroy0`：`WintunEndSession → WintunCloseAdapter → CloseHandle(shutdownEvent)`。**必须在上层 join 读线程之后调**，否则读线程可能还卡在 `WintunReceivePacket`。

> **manual-reset** 必须 `true`，否则 `WaitForMultipleObjects` 会消费事件，后续 wait 漏信号。

#### `TunAdapterV2` 基类（`adapter/TunAdapterV2.java`）

```java
public abstract class TunAdapterV2 implements Closeable {
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public abstract String name();
    public final boolean isClosed();
    public final ByteBuffer read();                       // 已 closed → ClosedChannelException
    public final void      write(ByteBuffer[] packet);    // 已 closed → ClosedChannelException
    public final void      wakeup();                      // 线程安全；已 closed → no-op
    public final void      close();                       // 幂等；契约：read 线程已退出

    protected abstract ByteBuffer read0()  throws IOException;
    protected abstract void       write0(ByteBuffer[] packet) throws IOException;
    protected abstract void       wakeup0() throws IOException;   // 让阻塞在 read0 的线程立即返回
    protected abstract void       destroy0() throws IOException;  // 实际释放资源
}
```

**为什么不继承 v1 `TunAdapter`**：
1. v1 的 `closed` 是 `private`，v2 的 `wakeup()` 要做 closed no-op 判断看不到；
2. v1 `checkOpen()` 抛 `IllegalStateException`（unchecked），v2 抛 `ClosedChannelException`（checked，`IOException` 子类）；
3. v1 `destroy()` 的语义近似 v2 的 `destroy0()`（best-effort 关 fd），父类方法不能承诺比自己更严的契约；
4. 继承会把两条代码路径强耦合，违反"v1/v2 独立"的工程原则。

可复用的部分是**三平台内部的重复代码**（v1/v2 在同平台里做相同的 open/iovec/错误映射），未来如需消重，应从三平台内部抽，不走跨版本继承。

#### Netty 集成（`TunChannelV2`）

`net/channel/TunChannelV2.java` 基于 `TunAdapterV2` 提供 netty `AbstractChannel` 实现。关闭流程严格按 v2 契约：

```
doClose() {
    device.wakeup();                             // 1. 唤醒 readLoop 里阻塞的 device.read()
    readLoop.shutdownGracefully(0, 1s, MS)       // 2. 等 readLoop 里 doRead 返回
            .syncUninterruptibly();
    device.close();                              // 3. 释放 fd / handle
}
```

`doRead` 捕获 `ClosedByWakeupException` 后直接 return，不 `fireExceptionCaught`、不反向触发 `unsafe.close`（避免重入 `doClose`）。

`DefaultTunChannelConfig` 构造参数从 `TunChannel` 放宽为 `io.netty.channel.Channel`（纯向下兼容），同时被 v1/v2 channel 复用。

#### 落地清单

v1 文件零改动；v1 只做了 6 个 helper 方法的访问修饰符从 package-private 放宽到 `public`（`LinuxNetworkInterface.setMTU/getMTU`、`DarwinNetworkInterface.setMTU0/getMTU0`、`DarwinNetworkRoutingTable.add0`、`WindowsNetworkInterface.getMTU0/setMTU0/addInterfaceAddress0`），纯加法不影响行为。

| 文件 | 说明 |
|------|------|
| `adapter/TunAdapterV2.java` | 基类：AtomicBoolean closed + read/write/wakeup/close + 4 原语 |
| `adapter/ClosedByWakeupException.java` | `IOException` 子类 |
| `adapter/linux/LinuxTunAdapterV2.java` | epoll + eventfd 实现 |
| `adapter/linux/jna/LibCEpoll.java` | epoll/eventfd/fcntl 绑定 + 常量 |
| `adapter/darwin/DarwinTunAdapterV2.java` | kqueue + EVFILT_USER 实现 |
| `adapter/darwin/jna/LibCKqueue.java` | kqueue/kevent/fcntl 绑定 + 常量 |
| `adapter/windows/WindowsTunAdapterV2.java` | WaitForMultipleObjects + Event 实现 |
| `net/channel/TunChannelV2.java` | netty `AbstractChannel`，wakeup + shutdownGracefully + close 三段 |
| `net/channel/DefaultTunChannelConfig.java` | 构造参数放宽为 netty `Channel`（v1/v2 共用） |

#### 验收标准（未跑）

每平台冒烟：
1. **正常流**：open → 写 100 包 → 读 100 包 → `wakeup + join + close` → 无 fd 泄漏。
2. **唤醒流**：open → 启一个专门线程 `read()` 阻塞 → 主线程 `wakeup()` → 读线程在 **50 ms 内** 抛 `ClosedByWakeupException` 退出 → `close()` → `lsof` / Process Explorer 验证全部资源释放。
3. **幂等 close**：两个线程同时 `close()`，`destroy0` 仅执行一次，不 double-free。
4. **rapid open-close**：100 次 open/wakeup/close 循环，fd 不累积、内存稳定。
5. **netty 路径**：`TunChannelV2.close()` 在 tun 完全空闲时 1 秒内完成（`readLoop.shutdownGracefully` 超时在 1 s 内）。

#### 已知风险

1. **Linux `epoll_event` packed 对齐**：x86_64 glibc `__attribute__((packed))` 12 字节；aarch64 不 packed 16 字节。当前 `LibCEpoll.EPOLL_EVENT_SIZE = 12` 仅适配 x86_64；arm64 上线前需按 `os.arch` 切换。
2. **Darwin `O_NONBLOCK` / `EAGAIN` 常量值与 Linux 不同**：平台子包常量独立定义，不可跨平台复用（`O_NONBLOCK` 0x0004 vs 04000，`EAGAIN` 35 vs 11）。
3. **Windows `shutdownEvent` manual-reset**：`CreateEvent` 第二参必须 `true`，否则 `WaitForMultipleObjects` 消费事件后续漏信号。
4. **UAF 窗口 — wakeup 与 close 的 TOCTOU**：`wakeup()` 检查 `closed=false` 后、调 `wakeup0()` 前，若另一线程完成 `close()` 并释放 fd，wakeup0 会踩空。标准契约（读线程自释放 / 外部 wakeup→join→close）排除此场景；违反契约的并发 close + wakeup 自负。
5. **`close()` 前未先 `wakeup()` 的后果**：读线程阻塞在 `epoll_wait` / `kevent` / `WaitForMultipleObjects`，`close(fd)` **不会主动唤醒**。
   - Linux/Darwin：最常见是 read 线程**永远挂死**，或 fd 数字被 OS 回收重用后读到陌生 fd 的数据；
   - Windows：`WintunEndSession` 期间若另一线程仍在 `WintunReceivePacket`，session 内部状态被破坏会崩溃。
   结论：`close()` 前**必须**先 `wakeup()` 并等读线程退出。`TunChannelV2.doClose()` 已内置此序。

### v2-2 write 路径对称唤醒 — 已关闭

tun write 在正常链路近乎即时返回；满载时非阻塞模式返回 EAGAIN，上层可感知。write 路径无需独立唤醒，**不单独推进**。

---

## 四、推进顺序建议

1. **OPT-7** —— 对上层收益最直接，成本 < 10 行。
2. **OPT-4** —— 热路径微优化，需要简单压测验证。
3. **OPT-6** —— 低优先级，随其他重构顺手清理。
4. **CONF-1 / CONF-2 / CONF-3** —— 需要真机或单测，建议作为回归测试的一部分。
5. **v2 验收** —— 代码已落地，剩单测与三平台冒烟；arm64 Linux 需补 `EPOLL_EVENT_SIZE` 按 `os.arch` 切换后才能上线。
