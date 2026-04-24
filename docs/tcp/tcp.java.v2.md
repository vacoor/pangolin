# v2 TCP 面向对象改造建议(2026-04-22)

> 背景:当前 `com.github.pangolin.routing.acceptor.tun.net.v2.tcp` 按"镜像 Linux 内核"
> 的规则实现(CLAUDE.md §5),函数名 / 文件组织都对着 `tcp_input.c` / `tcp_output.c` 走。
> 本文在"可以突破 CLAUDE.md 约束"的前提下,讨论如何把 v2 TCP 向 OO 方向重构,参考 gVisor
> (netstack)、FreeBSD(tcp_function_block)、Linux 内核的三种取舍。
>
> 性质:**讨论 / 规划文档**,非决议。落地前需要先回答 §六 的 4 个问题。

---

## 一、当前代码的真实痛点(挑最关键 5 个)

从文件规模已能看出信号:

```
TcpSock.java           1989 行
TcpOutput.java         1509 行
TcpMultiplexer.java    1055 行
Tcp4Multiplexer.java    541 行
TcpAck.java             531 行
```

### 痛点 1:`TcpSock` 是典型 God Object

它身兼四个角色 —— ① 状态容器(FSM state + writeSeq / rcvNxt 等 50+ 字段)、② 发送侧机制(send buffer / RTO / cwnd)、③ 接收侧机制(receive buffer / rcvWnd)、④ handler 回调锚点。这是 Linux `struct tcp_sock` 的直译。C 里这样写是历史包袱(struct 继承靠字段布局),Java 没有这个限制,完全可以拆。

### 痛点 2:静态单例 + 静态工具类破坏了可组合性

- `TcpOutput.INSTANCE.tcp_current_mss(sk)` —— 所有输出方法挂一个单例上,每个都 `sk` 作第一参
- `TcpOutOps.tcp_oow_rate_limited(...)`(static)—— 全局状态(per-host 挑战 ACK 桶)藏在静态字段里
- `SysctlOptions` —— 类级 static 配置

后果:① 测试难注 mock;② 跨连接的全局策略(挑战 ACK 桶、MIB 计数)实际是"一个进程只能有一个 TCP 栈实例"的隐含约束;③ 方法签名 `f(sk, arg1, arg2, ...)` 全是把 sk 当隐含 `this` —— 这就是 C 写法,不是 OO。

### 痛点 3:`TcpMultiplexer` 是多角色合体

读一遍就能识别:① 注册表(synRegistry / establishedRegistry / timewaitRegistry);② 状态机 dispatch(`tcp_rcv_state_process` / `tcp_validate_incoming`);③ LISTEN 端行为(`tcp_v4_conn_request` / `tcp_check_req`);④ TIME_WAIT 管理器;⑤ 全局策略守门人(OOW rate limit)。这些该是三个类,不是一个。

### 痛点 4:输入/输出路径"分了又没分"

表面上有 `TcpInput.java`(148 行)、`TcpOutput.java`(1509 行)、`TcpAck.java`、`TcpIncomingPreValidator.java`、`TcpRetransmitter.java`、`TcpOutOps.java`。但这些不是有职责的类,是按"kernel 文件对应"切的片段 —— 很多逻辑仍然回流到 `TcpMultiplexer` 或 `TcpSock`。这是**假模块化**:接口存在但职责没真正归属。

### 痛点 5:Linux 锚点是资产,也是包袱

方法名 `tcp_push_pending_frames` / `tcp_queue_skb` / `tcp_check_req` 对**懂 Linux 内核的人**是宝贵的锚点 —— 能直接去 `tcp_input.c` 搜函数名看逻辑,对抓包调试价值极高。但对**新接手 Java 代码的人**门槛高、自解释性差。这不是纯技术问题,是目标读者问题。OO 改造必须先回答这个问题。

---

## 二、参考:gVisor / FreeBSD / Linux 各自的 OO 取舍

### gVisor(Go,pkg/tcpip/transport/tcp)—— **最值得借鉴**

- **`endpoint`** 是主体对象(≈ Linux tcp_sock),但内部有明确分工:
  ```go
  type endpoint struct {
      state       EndpointState    // FSM
      snd         *sender          // 发送侧 — cwnd/ssthresh/rto/nagle/retrans/cc/recovery
      rcv         *receiver        // 接收侧 — rcvWnd/reassembly/OFO
      segmentQueue segmentQueue    // inbound 队列
      // ... ISN / options / peer addr / stats
  }
  ```
- **`sender` / `receiver` 是真正的类**,不是"片段"。`sender.handleRcvdSegment(seg)` 负责 ACK 处理、cwnd 更新、重传判断 —— **所有发送侧策略都在 sender 上**,不回调 endpoint。
- **FSM 用 enum + 方法分派**(不是 State Pattern):
  ```go
  func (e *endpoint) handleSegmentLocked(s *segment) {
      switch e.EndpointState() {
      case StateEstablished: e.rcv.handleRcvdSegment(s); e.snd.handleRcvdSegment(s)
      case StateSynSent:     e.handleSynSentSegment(s)
      // ...
      }
  }
  ```
- **SPI 接口精准**:`congestionControl`(reno/cubic)、`lossRecovery`(sack/reno)都是显式接口,但**其他内部协作是具体类之间直接方法调用**。不滥用 interface。
- **Segment 是值对象 + refcount**,不是可变的 skbuf。输入路径复制语义明确。
- **Stack 顶层**(`tcpip.Stack`)持有多个 `transportProtocol`、路由表、MIB 聚合。

gVisor 的价值取向是:**主体对象明确(endpoint)+ 两个核心子对象(sender/receiver)+ 少量 SPI**,其余不过度解耦。

### FreeBSD(C,OO-like)

- `struct tcpcb` 类似 Linux tcp_sock 但**更解耦**:
  - `t_cc` 指针挂 `cc_algo`(拥塞控制插件)
  - `t_fb` 指针挂 `tcp_function_block`(可替换整套 output 路径,BBR / RACK 作为替换栈层加载)
- **tcp_function_block 是 FreeBSD 的杀手锏**:整套"发送路径"作为模块加载,默认栈 vs RACK 栈 vs BBR 栈是**同一 SPI 的不同实现**,不是硬编码 if-else。这是比 Linux 更 OO 的地方。

对我们启发:**发送路径作为一套 SPI,而不是一堆分散 if** —— 但我们当前规模可能还不需要这么激进。

### Linux(C,OO 伪装)

- 继承链:`sock` → `inet_sock` → `inet_connection_sock` → `tcp_sock`,通过字段布局模拟
- vtable:`proto_ops` / `inet_connection_sock_af_ops` / `tcp_congestion_ops`
- **实事求是地说**:Linux TCP 不是 OO 优秀范例。它是"能跑且性能极限优化"的 C 代码。CLAUDE.md 要求镜像 Linux 的代价就是继承它的非 OO 风格。

---

## 三、推荐的 OO 改造方向 + 依据

核心主张:**以 gVisor 为蓝本,做 Endpoint + Sender + Receiver 的三元分解,保留 Linux 函数名作为 Javadoc 锚点,不追求"纯 OO 教科书"**。

### 目标包结构(方案 A,推荐)

```
v2.tcp/
├── TcpStack.java                    ← 顶层聚合:registries + policy + listeners
├── Endpoint.java                    ← 一条连接 = 控制块 + FSM (≈ 现在 TcpSock,但瘦身 1989→<600 行)
├── EndpointState.java               ← enum(已有 TcpConnectionState,rename 即可)
├── sender/
│   ├── Sender.java                  ← 发送侧主对象:skb 队列 + cwnd + nagle + push + fragment
│   ├── SendBuffer.java              ← 现 TcpSendBuffer
│   ├── RtoTimer.java
│   ├── Retransmitter.java           ← 现 TcpRetransmitter 的逻辑上收
│   └── SegmentEmitter.java          ← 从 TcpOutput 拆出:IP/TCP 组包 + Netty 写
├── receiver/
│   ├── Receiver.java                ← 接收侧主对象:OFO + 交付
│   ├── ReceiveBuffer.java           ← 现 TcpReceiveBuffer
│   └── WindowCalculator.java        ← rcvWnd / rcvWup 计算
├── listen/
│   ├── Listener.java                ← LISTEN 端:accept 队列 + syn 队列 + handshake 编排
│   └── Handshaker.java              ← 现 TcpHandshaker
├── input/
│   ├── SegmentValidator.java        ← 现 TcpIncomingPreValidator
│   ├── SegmentDispatcher.java       ← 顶层 rcv:路由到 listener / endpoint / TW
│   └── StateHandlers.java           ← FSM 分派(按 state 调 endpoint 相应方法)
├── segment/
│   ├── Segment.java                 ← 现 TcpSkb(rename + 不可变化)
│   └── IncomingPacket.java          ← 现 TcpPacketBuf 在 TCP 层的视图
├── cc/                              ← 已存在,保留
├── recovery/                        ← 已存在,保留
├── timer/                           ← 现 TcpTimerScheduler
├── timewait/                        ← 现 TcpTimewaitSock + 2MSL 管理
├── hook/                            ← 已存在
├── config/                          ← TcpConfig / SysctlOptions
├── mib/                             ← TcpMib / TcpMibStats
├── netty/                           ← 已存在
└── ext/                             ← 已存在
```

### 为什么这样拆(逐项依据)

**① `Endpoint` 瘦身为"控制块 + FSM"**
理由:gVisor 的 endpoint 也是这个定位。sender/receiver 分出去后,endpoint 留下的是:四元组 / 状态 / 选项协商结果 / handler 锚点 / 到 sender/receiver/timers 的引用 / state 迁移方法。**这个对象代表"一条连接这个概念",不是"一条连接的所有数据"**。

**② `Sender` 是最大的抽取收益**
当前 `TcpOutput`(1509)+ `TcpRetransmitter`(126)+ `TcpSendBuffer` + `TcpAck`(531,其中一半是 cwnd/ssthresh 更新)+ TcpSock 中的 send 字段,**分散在 4000+ 行里**。一个 `Sender` 把这些集中后,**ACK 处理 → cwnd 推进 → 窗口松动 → 下一轮 push** 的链路在一个类内闭环。调试单条连接的发送行为**不用跨 5 个类跳**。

**③ `Receiver` 同理,但收益较小**(recv 路径本身没那么分散,现有 `TcpReceiveBuffer` + `tcp_data_queue` + OFO 约 1000 行)。

**④ `Listener` 独立 —— 比 gVisor 更进一步**
gVisor 的 listener 也是 endpoint(LISTEN 状态)。但 LISTEN 和 ESTABLISHED 的字段使用完全不重合(LISTEN 要 accept queue + syn queue,不需要 sender/receiver)。**用继承或者共享 endpoint 都会带一堆空字段**。分成两个类更干净。Linux 实际上也走这个方向 —— `inet_connection_sock` 有 icsk_accept_queue,listen sock 和 established sock 是同一结构不同字段用途,在 Java 里没必要沿用。

**⑤ 杀掉 `TcpOutput.INSTANCE` / `TcpOutOps` 静态方法**
把方法搬到真正的 owner:
- `tcp_current_mss(sk)` → `Sender.currentMss()`
- `tcp_send_reset(sk)` → `Sender.sendReset()` 或 `Endpoint.sendReset()`
- `tcp_v4_send_reset(ctx, pkt)`(无 sock)→ `TcpStack.sendResetForUnmatched(ctx, pkt)`(全局发送器,因为无 sock)
- `tcp_oow_rate_limited` 的全局 host 桶 → `TcpStack.challengeAckLimiter`(实例字段)

依据:**"谁拥有状态,谁持有方法"**。全局状态挂栈上,per-sock 状态挂 endpoint/sender 上,消除 sk 作隐式 this。

**⑥ 保留 `@see tcp_write_xmit(tcp_output.c:...)` javadoc 锚点**
这是强烈的观点:**重命名方法,但 javadoc 保留锚点**。比如:

```java
class Sender {
    /** 推送待发队列。Mirrors {@code tcp_write_xmit} (tcp_output.c:L2525). */
    public int pushPending() { ... }
}
```

代价几乎为零,**保住了跨引 Linux 源码调试的核心资产**。改名的收益(自解释性)+ 锚点的收益(可溯源)= 两全。

**⑦ 不引入 State Pattern**
11 个状态 × 多个操作 = 11×N 个方法实现类,全是 1-2 行或空实现。用 `switch (endpoint.state())` 或 EnumMap 更清爽。gVisor / Linux 都不用 State Pattern,有原因。

**⑧ SPI 界面克制**
当前已有的 `CongestionControl` / `LossRecovery` / `RttEstimator` / `TcpSockInitializer` / `TcpSockHandler` **已经是恰当的 SPI 集合**。改造中不要在每层之间再插接口。内部类间用具体类型直接调用,JIT 友好、栈帧清晰。FreeBSD 的 tcp_function_block 是针对"整套 output 栈替换"这种强需求,我们当前没有这个需求。

---

## 四、不推荐的方向(和依据)

### ❌ 全盘重写,完全脱离 Linux 函数名

**依据**:抓包调试时,"这个 ACK 为什么没被接受" → Linux `tcp_validate_incoming` 源码 → 改造前类里搜 `tcp_validate_incoming` 能定位 → 改造后 `rename 后的方法名你得先查映射表`。对一个还在与 Linux 对齐过程中(tcp.java.md / tcp-gap-phase4.md 都是这个目标)的栈,这是巨大倒退。**保留锚点是硬要求**。

### ❌ 引入事件总线 / Reactor / 观察者链

**依据**:TCP 栈是硬实时关键路径,每个 ACK 到达要在 μs 级走完"收包 → validate → cwnd 更新 → 推发送 → 写 Netty"。任何发布订阅中间层都是性能和调试可读性双输。gVisor 也是直接 method call。

### ❌ Lombok / Builder / DTO 风格重构字段

**依据**:TCP 栈的字段间有大量隐式不变量(snd_nxt ≥ snd_una、rcv_wnd + rcv_nxt ≤ rcv_wup + rcv_wnd_max 等)。**字段是逻辑的一部分,不是 DTO**。轻量化 setter/getter 没问题,Builder 做这些对象就是本末倒置。

### ❌ 把 SPI 注入/DI 框架化(Spring / Guice)

**依据**:v2 TCP 是库代码,不是应用代码。框架进来后,你会发现每个类构造一个 `Endpoint` 都要过 BeanFactory,CI 里构造不出对象,单测炸裂。保持**显式构造器 + 工厂方法**。

### ❌ 拆包过细

比如把 sender 再拆成 `CwndManager` / `NagleChecker` / `FragmentSplitter` / `Pusher` —— 每个 < 50 行。**反例教科书**:逻辑的凝聚强度远超文件边界,跨 5 个小文件读一次 push 流程比在一个 500 行 Sender 里读更累。**凝聚 > 粒度**。gVisor sender.go 本身 2000+ 行,没人觉得是问题,因为内部高度内聚。

### ❌ 用 State Pattern 模拟 FSM

已经讲过。11 state × N operation = 11N 空壳方法。gVisor / Linux / FreeBSD 都不用,没理由我们用。

---

## 五、分阶段建议(按风险/收益比排序)

| Phase | 内容 | 代码影响 | 风险 | 收益 |
|-------|------|----------|------|------|
| **R0 — 准备** ✅ | 1. 建立一个可回归的集成测试基线(抓几条真实 TCP 对话的 pcap,可重放;至少 3WHS / 正常数据 / close / RST / TIME_WAIT 5 种)。<br>2. 定义"方法重命名对照表"(Linux 名 → 新名),作为后续所有 PR 附件。 | 0 | - | **没有这步不要动,否则 bug 是死的** |
| **R1 — 去静态** ✅ | 消灭 `TcpOutput.INSTANCE` / `TcpOutOps` 静态,全部降为实例方法。`TcpMultiplexer` 持有 `TcpOutput output` 实例字段,其他持有处随之传参。 | 中 | 中 | ★★★★ 解锁后续所有 OO 改造;纯机械,测试基线能验 |
| **R2 — 抽 Sender** ✅ | 从 `TcpSock` + `TcpOutput` + `TcpRetransmitter` + `TcpSendBuffer` + `TcpAck` 的发送侧中提出 `Sender` 类。R2.0-R2.3 全部完成:18+ facade API + **37 个字段物理下沉**(cwnd/ssthresh/RTT/CC状态/RACK/undo/F-RTO 全套)。TcpSock 发送侧仅剩 MSS/PMTU 等 endpoint 级配置。 | 大 | 中高 | ★★★★★ 发送侧从 4000+ 行跨 5 文件 → 聚合一处 |
| **R3 — 抽 Receiver** ✅ | 对称做接收侧。R3.0 / R3.1 / R3.2 均完成,Receiver 物理持有 rcvNxt/rcvWnd/rcvWup/rcvPaused 四字段。 | 中 | 中 | ★★★ 收益小于 Sender(原本已经比较聚合) |
| **R4 — 拆 TcpMultiplexer** 🚧 | 一分为四:`TcpStack`(registries + policy)+ `SockLookup`(纯查表)+ `SegmentDispatcher`(入站路由 + FSM switch)+ `Listener` 独立。R4.1 ✅;R4.2a 测试补齐 ✅;R4.2b 拆分 ⏳ 详见 §十四。 | 中 | 中 | ★★★★ TcpMultiplexer 瘦身到 <300 行 |
| **R5 — 重命名 + 锚点化** ✅ | 所有 `tcp_xxx` 方法改成 camelCase + javadoc 保留 `Mirrors Linux {@code tcp_xxx}` 锚点。**v2 范围全量完成 — snake_case 方法 0 残留**。 | 大(纯 rename)| 低 | ★★ 提升 Java 侧可读性 |
| **R6 — Segment 不可变化**(可选)| `TcpSkb` → `Segment`,尽可能不可变。refcount 显式。(rename ✅ `TcpSkb` → `TcpSegment`;不可变化未开工) | 中 | 中 | ★★ 健壮性提升,性能中性 |

**关键原则**:每个 Phase 独立 PR,每个 PR 通过集成测试基线。绝不要一次性大爆炸。

---

## 六、决策备忘(2026-04-22)

原 §六 的 4 个问题已与用户对齐,记录决策与执行影响:

### 决策 1:目标读者放宽到"读 Java 代码就能跟流程"

- 允许更激进的方法重命名:`tcp_push_pending_frames` → `pushPending`、`tcp_current_mss` → `currentMss` 等。
- **Javadoc 锚点仍然保留**(代价近零、调试收益大):
  ```java
  /** 推送待发队列。Mirrors {@code tcp_write_xmit} (net/ipv4/tcp_output.c). */
  public int pushPending() { ... }
  ```
- 内部文档化提升:每个核心类头写 5-10 行的"职责 / 不变量 / 线程模型"说明,而不是依赖 Linux 源码理解。

### 决策 2:补**单元测试**(不是集成 `TunTest` / `TunTestV2`)

- `TunTest` / `TunTestV2` 作为 sanity check 保留,但不再承担回归责任。
- R0 构造 **JUnit5 单元测试**,粒度针对改造后每个新类:
  - `SenderTest` — ACK 推进 / cwnd 更新 / Nagle / push / fragment,用 stubbed `SegmentEmitter` 验证出包序列
  - `ReceiverTest` — 乱序入队 / OFO 合并 / 交付顺序 / rcvWnd 计算
  - `EndpointTest` — FSM 迁移(每个合法/非法 transition)
  - `ListenerTest` — SYN 入队 / accept 队列 / SYN 重传 / syncookie 边界(未实现占位)
  - `SegmentDispatcherTest` — 路由到 listener / endpoint / TW / unmatched RST
  - `TcpStackTest` — 全局限流桶 / MIB 记账
- **R0 阶段可以先补"当前代码的"单元测试骨架**(针对 TcpSock / TcpMultiplexer 的公开行为),作为 R1-R6 回归网 —— 这样每个 Phase 重构都能跑同一组测试。
- 测试原则:**基于行为,不基于内部字段**。字段名在重构中会变,测试只断言入/出包序列 + 可观测状态(state / writeSeq / rcvNxt)。

### 决策 3:性能基线 = "WiFi 正常使用"(定性)

当前没有定量基线。建议在 R0 阶段顺手定义最小可观测指标,避免改造引入不可见回归:

| 指标 | 目标 | 如何观测 |
|------|------|---------|
| 单连接吞吐 | ≥ WiFi 裸跑的 80%(比如 iperf3 测裸 100Mbps,v2 TCP 代理后 ≥ 80Mbps) | 手工 iperf3 跑 30s,改造前后对比 |
| 建连延迟 | 3WHS 完成 ≤ 裸连接 + 20ms(backend connect 路径固定开销) | 浏览器 DevTools / wireshark TCP 时间线 |
| 长连接稳定性 | 10min 持续下载无断流 / 无 RST | 观察 `TcpMib` 计数 + 抓包 |
| 并发连接数 | 100 并发 TCP 不崩 | ab / wrk 跑 localhost 代理 |

**重要**:这是"可用性基线",不是"性能基线"。达不到说明 OO 改造引入了严重回归,需要回滚。**但达到了也不等于没回归** —— 对高延迟 / 丢包链路、大量短连接、慢启动行为等场景,肉眼不敏感。真正的性能基线留待后期 benchmark 工具化。

### 决策 4:接受 `tcp.java.md` 对齐表过渡期失真

- R2 / R4 期间 "v2 落点" 列可以短暂写 `Sender.pushPending` 这种过渡值,或标 `🚧 refactoring`。
- 每个 Phase PR 完成后同步更新 `tcp.java.md`。
- `tcp-gap-phase4.md` 不受影响(它只讲"未做的可选特性",与代码结构无关)。

### 决策 5:改造走独立 git 分支

- 建议分支名:`refactor/v2-tcp-oo`(与 `feature/v1.2.3-ai-lb` 并列)
- 每个 Phase 一个 sub-branch 或一组连续 commit,便于回退单个阶段:
  ```
  refactor/v2-tcp-oo              (长期保留)
    ├── r0-test-baseline          (PR merge 后保留)
    ├── r1-remove-statics
    ├── r2-extract-sender
    ├── r3-extract-receiver
    ├── r4-split-multiplexer
    ├── r5-rename-methods
    └── r6-immutable-segment      (可选)
  ```
- **合并策略**:rebase 或 squash,不要 merge commit,保持线性便于 bisect 定位回归。
- 分支基于当前 `feature/v1.2.3-ai-lb` 的 HEAD,重构期间 master 有变更可定期 rebase。

---

---

## 七、R0 完成记录(2026-04-23)

**测试框架**:JUnit 5.10.2 + Mockito 5.11.0 + AssertJ 3.25.3 + Surefire 3.2.5
(根 pom 在 `spring-boot-dependencies` 前 import 覆盖 spring-boot 2.0 带入的旧版 junit4 / mockito2。)

**测试基础设施**(`src/test/java/.../v2/tcp/harness/`):
- `FakeDnsEngine` — 空 DNS,满足 TcpMultiplexHandler 构造器契约
- `PacketFactory` — raw IPv4+TCP 包 builder(薄封装 `TcpPacketBuilder.buildRaw`),提供 `syn/ack/data/fin/rst` 快捷方法
- `TcpStackHarness` — `EmbeddedChannel(IpPacketCodec + TcpMultiplexHandler)` 封装,`sendInbound(ByteBuf)` / `readOutboundTcp() → Tcp4PacketBuf` 的 E2E 入口
- `CapturingInitializer` — 接纳所有 SYN,挂 `CapturingHandler` 录制 5 种 `TcpSockHandler` 回调事件,并暴露 `send(byte[])` 从栈侧主动发数据

**回归基线覆盖场景**(24 用例,全绿):

| 类 | 用例 | 覆盖的 refactor 风险点 |
|---|---|---|
| `TcpSequenceTest` | 9 | `TcpSequence` 32-bit 模运算(after/before/between + wrap-around) |
| `FourTupleTest` | 5 | 四元组 equals/hashCode,构造期 clone |
| `HandshakeDenyTest` | 2 | `tcp_v4_send_reset` 语义(seq=0 / ack=ISN+1);DENY 模式下 req 销毁 |
| `HandshakeHappyPathTest` | 1 | 完整 3WHS + `onEstablished` 回调 + `TCP_ESTABLISHED` 状态 |
| `DataTransferTest` | 1 | 入站 data → `onInboundData` 交付 + 延迟 ACK timer(需 `advanceTimeBy` 推进) |
| `SendAndAckTest` | 2 | `tcp_sendmsg → tcp_queue_skb → tcp_push_pending_frames → tcp_write_xmit` 发送链路;ACK 到达后 `snd_una` / `packets_out` 推进 |
| `RetransmissionTest` | 1 | RTO 到期 → 重传(相同 seq / payload,`snd_una` 不变) |
| `FinShutdownTest` | 1 | 对端 FIN → ACK + `CLOSE_WAIT` 迁移 + `onPeerFin` 回调 |
| `OutOfOrderDeliveryTest` | 1 | OFO 暂存 → gap 填补后按序交付 handler |
| `RstInboundTest` | 1 | 对端 RST → `onReset` 回调 + sock 销毁 |

**过程中的实战发现**:
- 栈的**延迟 ACK timer** 确实生效,纯 `writeInbound` 看不到 ACK,必须 `advanceTimeBy(200ms) + runScheduledPendingTasks()` 才能触发。这将是 R2/R4 重构的关键回归点。
- RST 路径的 `fireExceptionCaught` 冒泡到 EmbeddedChannel 会被自动 rethrow(测试代码需 try/catch 显式吞下,这是**预期行为**不是栈 bug)。

**未纳入 R0 基线的事项**(有意留给后续):
- RTO 指数退避的具体策略验证(当前只断言"不倒退",细节待专用测试)
- SACK / DSACK / cwnd 细节
- Window scaling / Timestamps 选项协商
- SYN 重传(LISTEN 端)
- TIME_WAIT 2MSL 超时

---

## 八、R1 完成记录(2026-04-23)

**目标**:消灭 TCP 主干上的 static `INSTANCE` 单例,全部降为 `TcpMultiplexer` 持有的
per-stack 实例字段。一切 per-sock 访问通过 `sock.multiplexer().xxx()` 路径。

### 子阶段

| 子阶段 | 对象 | 字段 | 调用点数 | 状态 |
|---|---|---|---|---|
| **R1.1** | `TcpRetransmitter.INSTANCE` | `TcpMultiplexer.retransmitter` | 4 | ✅ |
| **R1.2** | `TcpMibStats.INSTANCE` | `TcpMultiplexer.mib` | 24(主干)+ 4(TcpReceiveBuffer,R3 收尾)+ 1(handshake 无 sock,R4 收尾) | ✅(主干) |
| **R1.3** | `TcpOutOps` static 方法 | — | — | ⏭ 跳过(无 mutable 状态,utility class 合理) |
| **R1.4** | `TcpOutput.INSTANCE` | `TcpMultiplexer.output` | 39(全部迁移,隔离 RFC 5961 host-wide challenge ACK 桶) | ✅ |

### 关键改动

1. `TcpSock` 新增 `multiplexer` 字段 + `multiplexer()` / `multiplexer(TcpMultiplexer)` 访问器
2. `TcpMultiplexer` 新增 `configure(TcpSock)` 钩子注入 multiplexer 反向引用
3. listen sock 在 `init()` 里 configure,child sock 在 `moveToEstablished` 里 configure
4. `TcpHandshakerFactory` 构造签名增加 `TcpOutput output` 参数,propagate 到每个 `TcpHandshaker`
5. 所有 per-sock 调用改为 `sock.multiplexer().retransmitter() / mib() / output()`
6. `TcpMultiplexer` / `Tcp4Multiplexer` 内部调用用 `this.retransmitter / mib / output` 字段
7. `TcpRetransmitter.INSTANCE` 完全删除;`TcpMibStats.INSTANCE` / `TcpOutput.INSTANCE` 保留作为过渡期 fallback

### 回归验证

R0 基线 24 用例每个子阶段完成后立即回跑,**零回归**。特别是:
- `RetransmissionTest` 直接验证 R1.1(retransmitter 从 INSTANCE 走向实例)
- `HandshakeDenyTest` 直接验证 R1.4(challenge ACK 路径不破)
- `SendAndAckTest` + `DataTransferTest` 交叉验证 output + retransmitter + mib 协作

### 残留项(留给后续阶段)

| 调用点 | 原因 | 收尾时机 |
|---|---|---|
| `TcpReceiveBuffer` 4 处 `TcpMibStats.INSTANCE`(OFO/prune 计数) | 构造时未持栈引用,改造影响面超出 R1 | R3 抽 Receiver |
| `TcpOutput.tcp_send_challenge_ack_handshake` 1 处 `TcpMibStats.INSTANCE` | handshake 层 raw 出包无 sock 参数 | R4 拆 TcpMultiplexer 时 |
| `TcpTimerScheduler.INSTANCE` 未改 | 调度器本质是单进程 JVM timer,per-stack 意义不大(同一进程多栈复用 timer 是正常的) | 不计划改 |

### 现在的状态

- 5 个核心对象(TcpMultiplexer / TcpRetransmitter / TcpMibStats / TcpOutput / TcpHandshaker)都是 per-stack 或 per-sock 可实例化
- 同一 JVM 可以并存多个独立 TCP 栈(retransmitter 调度 + mib 计数 + challenge ACK 桶 + handshake state 都互不干扰)
- 下一阶段 R2(抽 Sender)可以开始 —— 已有 `sock.multiplexer().output()` 访问路径,抽出 Sender 后替换为 `sock.sender()` 只是名字迁移

---

## 九、R2 进行中(2026-04-23)

### R2 子阶段拆分

| 子阶段 | 目标 | 状态 |
|---|---|---|
| **R2.0** | 建 `Sender` 骨架,`TcpSock.sender` 注入路径 | ✅ |
| **R2.1** | 外部调用从 `sock.multiplexer().output()/retransmitter()` 迁至 `sock.sender()` facade | ✅ |
| **R2.2** | Sender API 完备化 — 新增 `sendmsg` / `backoff` / `resetBackoff` / `rtoMs` / `retransStamp` (get/set) / `tlpHighSeq` (get/set),外部调用点从 `sock.xxx()` 迁至 `sock.sender().xxx()` | ✅ |
| **R2.3** | 物理迁移 TcpSock 的 send-side 字段到 Sender(writeSeq / sndUna / cwnd / ssthresh / packetsOut / sendBuffer 等) | ⏳ 推迟 |

### R2.0 / R2.1 产出

**新增**:`v2.tcp.core.Sender` 类,13 个 facade 方法(currentMss / pushPending / sendFin /
sendReset / sendAck / sendChallengeAck / syncMss / retransmit / retransmitSkb /
sendLossProbe / rearmRto / scheduleRetransmit / scheduleLossProbe / onRtoTimeout)。

**TcpSock** 新增 `sender` 字段 + `sender()` / `sender(Sender)` 访问器。
`TcpMultiplexer.configure(TcpSock)` 同时注入 `multiplexer` + `sender`。

**调用点迁移**:全部外部调用从 `sock.multiplexer().output().xxx(sock)` 或
`sock.multiplexer().retransmitter().xxx(sock)` 改为 `sock.sender().xxx()`。
剩余 `sock.multiplexer().output()/retransmitter()` 引用**只存在于** `Sender.java`
自己的 facade 实现里,完全符合 R2.0 设计预期。

### R2 当前性质

- Sender **已经是代码里的一等公民**,具备 18 个语义方法,调用方通过它访问发送行为;
- Sender **仍然是 facade**,发送侧 mutable state 仍在 TcpSock 里(delegate);
- 这个状态下,**外部调用方对 "Sender 未来拿到物理字段" 完全无感** —— 字段下沉时只需要
  替换 Sender 方法的实现,不改调用点。

### R2.3 推迟原因(调研结论)

按 R0 基线盘点后发现,TcpSock 发送侧字段**彼此计算耦合深**:
- `rtoMs()` 依赖 `srttUs / rttvarUs / rtoBackoffShift`
- `tcpTryUndoRecovery()` / `tcpTryUndoLoss()` 依赖 `undoMarker / retransStamp / highSeq`
- `onTimeoutByCc()` 依赖 `cwnd / ssthresh / tlpHighSeq / congestionState`
- `backoffRto()` / `rtoMs()` 调用链跨 CC / Recovery / Retransmit

**按字段小步迁会导致 TcpSock ↔ Sender 双向耦合**(一半字段在 Sender、另一半在 TcpSock,
方法被迫跨对象读)。正确做法是**一次性** 把 `{RTO 状态 + 拥塞状态 + 发送队列}` 整组字段
+ 相关计算方法 一起下沉,预计 500-800 行跨多文件改动,不是一轮对话能覆盖。

**决定**:R2.3 留作未来专题工程,当前 Sender 的 facade 形态在功能上已经足够"看起来像对象"。
真正要物理下沉时应按 gVisor `sender.go` 的组织重写,包含 cwnd/ssthresh/nagle 算法的聚合。

### R2.0-R2.2 产出汇总

**Sender API**(共 18 个方法):

| 分类 | 方法 |
|---|---|
| 出站主入口 | `sendmsg(ByteBuf, boolean)` |
| MSS / push | `currentMss()` / `pushPending()` / `syncMss(int)` |
| 控制段 | `sendFin()` / `sendReset()` / `sendAck()` / `sendChallengeAck(boolean)` |
| 重传 | `retransmit()` / `retransmitSkb()` / `sendLossProbe()` |
| RTO 调度 | `rearmRto()` / `scheduleRetransmit()` / `scheduleLossProbe(long)` / `onRtoTimeout()` |
| RTO 状态 | `backoff()` / `resetBackoff()` / `rtoMs()` |
| 重传/TLP 标记 | `retransStamp()` / `retransStamp(long)` / `tlpHighSeq()` / `tlpHighSeq(int)` |

**调用点状态**:
- 外部调用(TcpChannel / TcpPassthroughInitializer / TcpAck / TcpOutput / TcpIncomingPreValidator / TcpRetransmitter):**全部**走 `sock.sender().xxx()`
- 剩余 `sock.xxx()` 直接访问都是**接收侧或 TcpSock 内部计算** (不属于 Sender 范围)
- Sender 自身的 facade 实现 delegate 到现有路径,保留物理字段在 TcpSock

---

## 十、R3 进行中(2026-04-23)

### R3 子阶段

| 子阶段 | 目标 | 状态 |
|---|---|---|
| **R3.0** | 建 `Receiver` 骨架,`TcpSock.receiver` 注入路径 | ✅ |
| **R3.1** | 外部调用 `sock.rcvNxt()` / `sock.receiveBuffer()` 等迁至 `sock.receiver()` facade | ✅ 25 处迁移 |
| **R3.2** | 物理迁移 TcpSock 接收侧字段到 Receiver + 清理 TcpReceiveBuffer 的 `TcpMibStats.INSTANCE` 残留 | ⏳ 推迟 |

### R3.0-R3.1 产出

**Receiver API**(12 个方法):

| 分类 | 方法 |
|---|---|
| 期望序号 | `rcvNxt()` / `rcvNxt(int)` |
| 通告窗口 | `rcvWnd()` / `rcvWnd(int)` / `rcvWup()` / `rcvWup(int)` |
| 背压 | `paused()` / `paused(boolean)` |
| 缓冲 | `buffer()` |
| 窗口计算 | `receiveWindow()`(== `tcp_receive_window`) |
| ACK 调度 | `enterQuickAck(int)` / `addAckPending(int)` / `clearAckPending(int)` |

**Receiver 范围边界**:接收**行为**(window / OFO / 背压 / quickack / ACK 调度)纳入。
Per-sock **静态配置**(`timestampEnabled` / `rcvBuf` / `pawsRejected`)不纳入 —— 它们是选项协商结果或常量阈值,不属于"接收行为"。

**调用点**:TcpOutput(16 处)+ TcpIncomingPreValidator(6 处)+ TcpChannel(3 处)= 25 处全部从 `sock.rcvXxx() / sock.receiveBuffer() / sock.tcp_receive_window()` 迁移到 `sock.receiver().xxx()`。

**未涉及的路径**:TcpMultiplexer 内部使用 `sk.xxx()` 的接收侧字段访问未迁 — 那是 mux 内部实现,属于"包装盒子内部",不在 R3.1 的外部接口迁移范围。R3.2 物理字段下沉时才会处理。

### R3.2 推迟

与 R2.3 同理:`rcvNxt` / `rcvWnd` 等字段被 TcpMultiplexer / TcpIncomingPreValidator 等**接收路径主循环**频繁内部使用,物理下沉涉及大量计算方法迁移。适合作为独立专题工程。

---

## 十一、当前整体进度(2026-04-23 快照)

| 阶段 | 完成度 |
|---|---|
| R0 测试基线(24 用例 E2E) | ✅ |
| R1 去静态(4 个 per-stack 对象) | ✅ |
| R2 抽 Sender(R2.0-R2.2 facade + R2.3 物理下沉 **37 字段**)| ✅ |
| R3 抽 Receiver(R3.0-R3.1 facade + R3.2 物理下沉 4 字段) | ✅ |
| R4.1 抽 Listener(3 字段物理迁移 + API 封装) | ✅ |
| R4.2a 测试补齐(DispatcherRoutingTest + RegistryLifecycleTest,+10 用例) | ✅ |
| R4.2b-1 抽 SockLookup 纯函数类 | ✅ |
| R4.2b-2 抽 TcpStack(registries + 生命周期 + 全局策略) | ✅ |
| R4.2b-3 重命名 TcpMultiplexer → SegmentDispatcher | ✅ |
| R4.2b-4 FSM 方法下沉(8 子阶段 a-h)到 Sender/Receiver/Listener/TcpTimewaitSock | ✅ |
| R4.2b-i 清扫 SegmentDispatcher 遗留(删 __inet_lookup_skb delegate 等) | ✅ |
| R4.2c checkReq 迁到 Listener(带 dispatcher 参数) | ✅ |
| R5.x TcpSock.multiplexer() → TcpSock.stack() rename | ✅ |
| R5 rename + javadoc 锚点化 | ✅ **全量完成** |
| R6 Segment rename(TcpSkb → TcpSegment) | ✅ |
| R6 Segment 不可变化 | ⏳ 未开工 |
| R7.1 TcpSock 续瘦:probe/keepalive/linger2(9 字段 + 16 方法)迁到 Sender | ✅ |

### Sender 物理持有字段(R2.3 完成后,共 37 个)

| 组 | 字段 |
|---|---|
| 发送序号 | `sndUna / sndNxt / writeSeq` |
| 发送窗口 | `sndWnd / maxWindow / sndWl1 / sndSml` |
| 拥塞窗口 | `cwnd / ssthresh / priorCwnd / priorSsthresh / sndCwndStampMs / sndCwndUsed / isCwndLimited` |
| 飞行计数 | `packetsOut / sackedOut / lostOut` |
| RTT / RTO | `srttUs / rttvarUs / rtoBackoffShift / retransStamp / tlpHighSeq` |
| CC 状态 | `dupacks / congestionState / highSeq / caIncrCounter` |
| Undo / F-RTO | `undoMarker / undoRetrans / frtoHighmark / frtoCounter` |
| RACK | `rackMstamp / rackRttUs / rackReoWndSteps / rackReoWndPersist / rackDsackSeen / delivered / rackLastDelivered / rackAckPriorDelivered` |
| 统计 | `bytesAcked` |

### Receiver 物理持有字段(R3.2 完成后,共 4 个)

`rcvNxt / rcvWnd / rcvWup / rcvPaused`

### Listener 物理持有字段(R4.1 完成后,共 3 个)

`listenSock / synRegistry / maxSynBacklog`

### TcpSock 剩余字段性质

TcpSock 剩下的字段都是 **endpoint 级别的杂项配置 + 协议状态**,不属于发送/接收行为:
- MSS/PMTU:`rcvMss / mss / mssCache / pmtuCookie / tcpHeaderLen / dstMtu / windowClamp / sndWscale / rcvWscale`
- Handshake/TS 选项:`timestampEnabled / recentTimestamp / tsRecentStamp`
- 接收侧细节:`quickAckCount / ackTimeoutMs / pingpongCount / ackPending / rcvBuf / rcvSsthresh / skShutdown / icskAck*`
- Probe/keepalive:`probeBackoffShift / probesOut / userTimeoutMs / keepalive* / linger2`
- 挑战 ACK per-sock:`ipv4TcpChallengeTimestamp / ipv4TcpChallengeCount`
- DSACK:`dsackStart / dsackEnd`
- 杂项:`skErr / lastOowAckTimeMs / tos / lastRecvTimeMs / lastSendTimeMs`

**当前代码形态**:
- 5 个核心对象去单例,支持多栈并存
- **Sender 物理持有 37 字段**,是发送侧完整载体(从 facade 升级为实体对象)
- **Receiver 物理持有 4 字段**,接收侧核心窗口状态
- **Listener 物理持有 3 字段**,LISTEN 端全套
- TcpSock 从 god object 瘦身为 endpoint 控制块,职责清晰
- R0 基线 24 用例在每个子阶段后回归,**全程零回归**

---

## 十二、R5 试点(2026-04-23)

### 试点范围

把 `TcpOutput` 的 **8 个顶层 public 方法** rename 为 camelCase,Javadoc 保留 `Mirrors Linux {@code tcp_xxx}` 锚点:

| 原名 | 新名 | Linux 锚点 |
|---|---|---|
| `tcp_current_mss` | `currentMss` | `tcp_current_mss` |
| `tcp_send_fin` | `sendFin` | `tcp_send_fin` |
| `tcp_send_reset` | `sendReset` | `tcp_send_active_reset` |
| `tcp_send_ack` | `sendAck` | `__tcp_send_ack` |
| `tcp_send_challenge_ack` | `sendChallengeAck` | `tcp_send_challenge_ack` |
| `tcp_sync_mss` | `syncMss` | `tcp_sync_mss` |
| `tcp_retransmit_skb` | `retransmitSkb` | `tcp_retransmit_skb` |
| `tcp_send_loss_probe` | `sendLossProbe` | `tcp_send_loss_probe` |

### 变更范围

- TcpOutput 方法定义 8 处(签名 rename)
- 调用点 25 处(Sender facade 2 × 9 = 部分、TcpRetransmitter、TcpMultiplexHandler 等)
- Javadoc 引用 10+ 处(Linux 锚点用下划线形式**保留**,本地方法名用 camelCase)

### 方法论验证

R5 的工作流程验证:
1. `sed -i 's|\btcp_xxx\b|xxxXxx|g'` 批量 rename(方法定义 + 调用点 + Javadoc 引用)
2. sed 会**误伤 Javadoc 锚点**(把 `Mirrors Linux {@code tcp_send_fin}` 改成 `sendFin`)
3. **必须**在第 2 步后人工修复 Javadoc `Mirrors Linux {@code ...}` 和 `对齐 Linux {@code ...}` 里的锚点为 Linux 原名
4. R0 回归验证

工作量估算:每个 Linux 函数 rename 大约 5-15 分钟人工修 Javadoc + 批量 sed。

### R5 全量完成统计

| 文件 | rename 的方法数 |
|---|---|
| TcpOutput | ~40(public + private) |
| TcpMultiplexer | ~25 |
| Tcp4Multiplexer | ~6 |
| TcpAck | 4 |
| TcpIncomingPreValidator | 4 |
| TcpSock | 2 |
| TcpInput / TcpOutOps / SysctlOptions | ~7 |
| **总计** | **~90 个方法 rename** |

**Javadoc 锚点保留**:所有 `{@code xxxYyy}` 在 "Mirrors Linux" / "对齐 Linux" / "in the kernel" / "tcp_input.c:xxx" 等上下文中被批量修复回 Linux 原下划线形式。

### R5 验证过的工作流

1. `sed -i 's|\btcp_xxx\b|camelName|g'` 全仓批量 rename(主代码 + 测试代码都要包含)
2. `sed -i 's|{@code camelName}|{@code tcp_xxx}|g'` 修复被误伤的 Javadoc 锚点
3. `mvn compile` 验证结构正确
4. `mvn test` 跑 R0 24 用例回归

单批次最多 30+ 方法一起做,sed 全局替换后单次编译 + 单次回归足够。

### 关键跳过

- `tcp_reset` 这类短名字用 `resetIncoming` 避免和 `TcpOutput.sendReset` 混淆
- `tcp_done` → `tcpDone`(不用 `done`,避免 JDK/Netty 常见方法冲突)
- `tcp_shutdown` → `shutdownStack`(区别于 AutoCloseable.close)
- `tcp_rcv` 保留短名 `rcv`(TcpMultiplexer 主入口,短名合适)

---

## 十三、R4.2 架构终稿(2026-04-23 决策)

### R4.2a 完成:测试补齐

commit `ead90ed1`,新增 10 个测试用例:
- `DispatcherRoutingTest`(5):未命中 ACK → RST、未命中 RST 静默、TW bucket 迟到 FIN / RST / SYN 重用三分支
- `RegistryLifecycleTest`(5):tcpDone 摘 established、timeWait 迁移、inet_twsk_kill、2MSL 超时、多 bucket 4-tuple 隔离

反射探针锁住 `establishedRegistry`/`timewaitRegistry` 形态,R4.2b 搬家时必须同步更新测试,构成结构化护栏。

### R4.2b 调研结论(7 个参考栈)

调研 Linux / gVisor / FreeBSD / Netstack3(Rust)/ lwIP / Seastar / mTCP,核心发现:

| 维度 | 多数派 | 少数派 | v2 当前 | v2 决策 |
|------|-------|-------|--------|--------|
| 注册表拆分 | 双表(listen 按端口 / est 按 4-tuple) | 单表 | 已对齐双表(listenSock + established + timewait) | **保留** |
| Listener 独立 | 5/7 独立(Netstack3 / lwIP / Seastar / mTCP / FreeBSD) | 2/7 共用(Linux / gVisor) | R4.1 已独立 ✅ | **保留** |
| Demuxer / Dispatcher 分离 | gVisor 明确分(transportDemuxer + dispatcher) | 其他合一 | 合一(TcpMultiplexer) | **要分**(借鉴 gVisor) |
| FSM switch 位置 | 顶层函数(Linux / FreeBSD / lwIP,3 票) | 对象方法(gVisor / Netstack3,2 票) | `TcpMultiplexer.rcvStateProcess` | **顶层函数**(Dispatcher 私有方法,对齐多数派) |
| Forwarder 模式 | gVisor 独有 | 其他直接完成握手 | `TcpSockInitializer.onRequest` 已是此模式 | **保留** |

### 目标架构

```
TcpStack           (~250 行,concrete)  注册表 + 全局策略 + 生命周期
  ├── establishedRegistry / timewaitRegistry
  ├── listener(R4.1 Listener 实例)
  ├── config / tcpGroup / initializer / mib / output / retransmitter
  ├── 生命周期:configure / tcpDone / timeWait / inet_twsk_kill
  └── has-a SegmentDispatcher

SockLookup         (~50 行,纯函数)    lookup(pkt) → SockCommon
  └── 查 established → timewait → req → listenSock

SegmentDispatcher  (abstract,~50 行)  入站外壳
  └── Ipv4SegmentDispatcher(concrete,~150 行)  IPv4 特化路由

Listener / Sender / Receiver / TcpTimewaitSock
  └── 吸收原 TcpMultiplexer 的 ackIncoming / dataQueue /
      finIncoming / timewaitStateProcess / 各 timer 回调

TcpSock                                 数据 + 组合根,**无** receive / handle 行为方法
```

### 关键决策(和依据)

1. **TcpSock 走"数据+状态"派**(Linux / FreeBSD / lwIP 路线,5/7 中 3 票),不走 gVisor 的 "Active Object" 派。依据:R2/R3/R4.1 一直在把字段/行为**搬出** TcpSock,给它加 `receive()` 是反方向;TcpSock 这个名字本身暗示 Linux `struct sock` 谱系。

2. **FSM switch 留在 Dispatcher 私有方法**,不是 `sock.process()`。依据:对齐 Linux `tcp_rcv_state_process(sk, skb)` 顶层函数模式;Dispatcher 仍薄(~150 行),switch body 全是 delegate 到 Listener / Sender / Receiver,不会二次膨胀。

3. **不引入 `StateHandlers` / State Pattern**。依据:§三.⑦ 原则不变 —— "11 state × N operation = 11N 空壳方法" 是反例。switch 保留但薄到不必独立命名。

4. **状态迁移(ESTABLISHED → CLOSE_WAIT 等)由检测者直接 `sock.state(X)` 写**,不走 FSM 仲裁器。依据:对齐 Linux `tp->state = TCP_CLOSE_WAIT` 风格;每种迁移都有天然 owner(Receiver 判 FIN、Sender 判 ACK),不必中心化。

5. **`TcpMultiplexer` 抽象基类消失**。依据:v4/v6 差异下沉到 `SegmentDispatcher` 子类(`Ipv4SegmentDispatcher`),`TcpStack` 协议无关、concrete。

### 类映射对照表

| 原 `TcpMultiplexer` 成员 | R4.2 后去处 |
|------------------------|-------------|
| `establishedRegistry` / `timewaitRegistry` / `listener` | → `TcpStack` |
| `config` / `tcpGroup` / `initializer` / `handshakerFactory` / `mib` / `output` / `retransmitter` | → `TcpStack` |
| `init()` / `configure(sk)` / `tcpDone()` / `inet_csk_destroy_sock()` / `timeWait()` / `inet_twsk_kill()` / `inet_twsk_reschedule()` / `closeState()` / `shutdownStack()` | → `TcpStack` |
| `write(FourTuple, ByteBuf)` / `sendmsg(sk, ...)` | → `TcpStack`(用户出站 API) |
| `__inet_lookup_skb()` | → `SockLookup`(新类) |
| abstract `rcv()` / `send_reset()` / `conn_request()` / `sendSynAck()` / `syn_recv_sock()` / `inet_rtx_syn_ack()` | → `SegmentDispatcher`(具体子类) |
| `sk_acceptq_is_full()` / `checkReq()` / `synackRttMeas()` / `addToHalfQueue()` / `moveToEstablished()` | → `Listener` |
| `ackIncoming()` / `dataQueue()` / `queue_and_out()` / `dataSndCheck()` / `ackSndCheck()` / `pushPendingFrames()` / `initTransfer()` / `initWl()` / `initializeRcvMss()` | → `Sender`(多数)/ `Receiver`(ackSndCheck 等接收侧 ACK 调度) |
| `finIncoming()` / `outOfWindow()` / `dataQueueOfo()` / `sendDelayedAck()` / `sequenceAcceptable()` | → `Receiver` |
| `delackTimer()` / `probeTimer()` / `keepaliveTimer()` / `armProbe0()` / `armKeepalive()` | → `Sender / Receiver`(按语义) |
| `timewaitStateProcess()`(在 `Tcp4Multiplexer`) | → `TcpTimewaitSock` 成员方法 |
| `rcvStateProcess()`(在 `Tcp4Multiplexer`) | → `SegmentDispatcher` 私有 FSM switch |

### 执行步骤(4 个独立 commit)

| 步骤 | 内容 | 预期代码影响 | 测试基线 |
|-----|------|------------|---------|
| **R4.2b-1** | 抽 `SockLookup` 纯函数类;`TcpMultiplexer` 持有 `SockLookup lookup` 实例;`__inet_lookup_skb` → `lookup.lookup(pkt)` | ~50 行新文件 + 1 个字段 + 方法 delegate | 34/34 |
| **R4.2b-2** | 创建 `TcpStack`;把 registries / lifecycle / 全局策略字段和方法搬家;`TcpMultiplexer` 降级为 `TcpStack` 的薄包装(暂留兼容) | ~250 行新文件 + `TcpMultiplexer` 瘦身 | 34/34 |
| **R4.2b-3** | 创建 `SegmentDispatcher` 抽象类 + `Ipv4SegmentDispatcher`;把 `rcv` / `rcvStateProcess` / instanceof 分支从 `Tcp4Multiplexer` 搬过来;`TcpMultiplexHandler` 改用 Dispatcher | `Tcp4Multiplexer` 瘦身到 <200 行 | 34/34 |
| **R4.2b-4** | 把 `ackIncoming / dataQueue / finIncoming / timewaitStateProcess / timer 回调`等处理方法迁到 Sender / Receiver / Listener / TcpTimewaitSock 成员方法;Dispatcher 的 FSM switch 全部变成 delegate 调用 | 行数跨文件移动,净增加 0-100 行 | 34/34 |

R4.2b-3 之后,删除 abstract `TcpMultiplexer` 基类(可能 R4.2b-4 再删,视依赖情况)。

---

## 十四、R4.2 + R5.x 完成后的终态(2026-04-24)

### 类尺寸对比

| 类 | R4.2 前 | R4.2 后 | 变化 |
|---|---|---|---|
| `TcpMultiplexer` → `SegmentDispatcher` | 1142 | **156** | **-86%** 🎯 |
| `Tcp4Multiplexer` → `Ipv4SegmentDispatcher` | ~500 | 441 | 路由外壳 + IPv4 特化 |
| `TcpStack`(新,concrete) | - | 300 | 注册表 + 生命周期 |
| `SockLookup`(新,纯函数) | - | 68 | lookup(pkt) → SockCommon |
| `Sender` | 601 | **814** | +35%,吸收 timer + sendmsg + ackIncoming + shutdown + initWl |
| `Receiver` | 212 | **520** | +145%,吸收接收 FSM + ACK 调度 + initializeRcvMss |
| `Listener` | 86 | **253** | +194%,吸收 checkReq + synackRttMeas |
| `TcpTimewaitSock` | 166 | 269 | +62%,吸收 handleIncoming |

### 最终架构

```
TcpStack (concrete, 300 行)
 ├─ 注册表:establishedRegistry / timewaitRegistry / listener / lookup
 ├─ 全局组件:config / mib / output / retransmitter / handshakerFactory / initializer
 ├─ 生命周期:tcpDone / timeWait / inet_twsk_kill / moveToEstablished / closeState
 └─ NEW_STATE 常量表
    ↑ extends
SegmentDispatcher (abstract, 156 行)
 ├─ 入站 abstract 接口:rcv / send_reset / conn_request / sendSynAck / syn_recv_sock / inet_rtx_syn_ack
 ├─ init / configure:sock 装配 + Sender/Receiver 创建
 ├─ initTransfer:ESTABLISHED 初始化(绑 timer action + onEstablished + armKeepalive)
 └─ write / sendmsg / consume:用户/测试入口的薄 delegate
    ↑ extends
Ipv4SegmentDispatcher (concrete, 441 行)
 ├─ rcv / tcp_v4_rcv / tcp_v4_do_rcv / rcvStateProcess(FSM switch)
 ├─ tcp_v4_conn_request / tcp_v4_syn_recv_sock / tryEstablish
 ├─ dataQueueAndPostProcess:ESTABLISHED 段 orchestration
 └─ IPv4 特化出包

SockLookup (68 行) — 纯函数 lookup(pkt) → SockCommon
Listener (253 行) — LISTEN 聚合 + checkReq + synackRttMeas
Sender (814 行) — 发送侧 owner
Receiver (520 行) — 接收侧 owner
TcpTimewaitSock (269 行) — TW bucket + handleIncoming
```

### 关键决策验证(全部落地)

1. **TcpSock 保持"数据 + 状态"派,无 receive / handle 行为方法** ✅
2. **FSM switch 留在 `Ipv4SegmentDispatcher.rcvStateProcess` 私有方法**(对齐 Linux `tcp_rcv_state_process`)✅
3. **不引入 StateHandlers / State Pattern**,switch 薄到不必独立命名 ✅
4. **状态迁移由检测者直接 `sock.state(X)` 写** ✅
5. **abstract `TcpMultiplexer` 基类消失**(原名迁为 SegmentDispatcher) ✅
6. **R5.x** 把 `TcpSock.multiplexer()` 重命名为 `stack()`,命名对齐实际类型 ✅

### 剩余技术债(可选,不影响正确性)

1. `SegmentDispatcher extends TcpStack` 仍是继承关系;未来若需要更严格的 stack / dispatcher 概念分层,可改组合(需把所有 `mib.inc` 改 `stack.mib.inc`,工作量大,收益有限)。
2. `SegmentDispatcher.configure` 留在 Dispatcher 而非 Stack(因需要 `sk.stack(this)` 的 'this' 为 SegmentDispatcher 子类)。
3. `TcpSock` 仍 1969 行 — 大量 endpoint 级配置(MSS / PMTU / keepalive / TS options 等)。继续拆需要专题规划。

### 测试护栏

34/34 E2E + 单元测试全程绿,其中:
- 10 个 R4.2a 新增(DispatcherRoutingTest + RegistryLifecycleTest)
- 反射探针 `TcpStack.class.getDeclaredField("establishedRegistry")` 锁定 registries 形态
- 时间敏感路径(2MSL / delayedAck / keepalive / probe0)通过 `advanceTimeBy` 覆盖

---

## 十五、一句话版本
