# TCP 协议栈 QA 分析报告

**分析范围**: `com.github.pangolin.routing.acceptor.tun.net.handler.tcp`
**对照基准**: Linux 内核 TCP 协议栈 (`net/ipv4/tcp*.c`, `include/net/tcp.h`)
**分析日期**: 2026-04-01

---

## 一、已确认的 BUG

### BUG-1: `SHUTDOWN_MASK` 值错误（`TcpConstants.java:72`）

**位置**: `internal/TcpConstants.java`

```java
// 当前（错误）
int SHUTDOWN_MASK = 2;

// Linux 内核定义（include/net/sock.h）
#define RCV_SHUTDOWN  1
#define SEND_SHUTDOWN 2
#define SHUTDOWN_MASK 3   // = RCV_SHUTDOWN | SEND_SHUTDOWN
```

**影响**: `tcp_done()` 中执行 `tp.sk_shutdown = TcpConstants.SHUTDOWN_MASK`，只设置了 `SEND_SHUTDOWN`（bit 1），未设置 `RCV_SHUTDOWN`（bit 0）。后续任何针对 `sk_shutdown & RCV_SHUTDOWN` 的检查（如 `tcp_fin()` 中对 `sk_shutdown |= RCV_SHUTDOWN` 的依赖）行为不符合预期。

**修复**: 将 `SHUTDOWN_MASK = 2` 改为 `SHUTDOWN_MASK = 3`。

---

### BUG-2: `tcp_reset_check` 位运算符错误（`TcpInput.java:1462`）

**位置**: `core/TcpInput.java`，方法 `tcp_reset_check`

```java
// 当前（错误）：| 导致表达式恒为 true
return seq == tp.rcv_nxt - 1
    && 0 != ((1 << tp.state().ordinal()) | (TCPF_CLOSE_WAIT | TCPF_LAST_ACK | TCPF_CLOSING));

// 应为（正确）：& 检查当前状态是否在允许集合内
return seq == tp.rcv_nxt - 1
    && 0 != ((1 << tp.state().ordinal()) & (TCPF_CLOSE_WAIT | TCPF_LAST_ACK | TCPF_CLOSING));
```

**影响**: 由于右侧 `TCPF_CLOSE_WAIT | TCPF_LAST_ACK | TCPF_CLOSING` 为固定非零常量，`|` 运算后结果恒为非零，导致该函数在 `seq == rcv_nxt - 1` 时对**任意状态**都返回 `true`。

对照 Linux 内核，此函数（`tcp_reset_check`）仅应在 CLOSE_WAIT、LAST_ACK、CLOSING 这三种状态下返回 `true`，其他状态（包括 ESTABLISHED）应返回 `false`。

**安全影响**: 任意 TCP 连接在 `seq = rcv_nxt - 1` 时均可被伪造的 RST 报文重置，而不限于关闭阶段的状态。

**修复**: 将 `|` 改为 `&`。

---

### BUG-3: `get_random_u32_inclusive` 端点包含错误（`TcpInput.java:2006`）

**位置**: `core/TcpInput.java`，方法 `get_random_u32_inclusive`

```java
// 当前（错误）：返回 [a, b)，不包含 b
private int get_random_u32_inclusive(int a, int b) {
    return a + random.nextInt(b - a);
}

// 应为（正确）：返回 [a, b]，包含 b
private int get_random_u32_inclusive(int a, int b) {
    return a + random.nextInt(b - a + 1);
}
```

**影响**: Challenge ACK 速率限制计数器的初始化范围少 1，与 Linux 内核 `get_random_u32_inclusive` 语义不符（RFC 5961 §7 要求）。

---

### BUG-4: `tcp_select_initial_window` 中无用变量与浮点精度问题（`TcpOutput.java:196`）

**位置**: `core/TcpOutput.java`，方法 `tcp_select_initial_window`

```java
// 当前（存在问题）
int i = _ilog2(space);                                        // 计算了但从未使用
rcv_wscale.set(clamp(ilog2(space) - 15, 0, TCP_MAX_WSCALE)); // 浮点 ilog2，space=0 时异常
```

**问题**:
1. `i = _ilog2(space)` 计算结果从未被使用，是死代码。
2. `ilog2(space)` 使用 `Math.log(a) / Math.log(2)` 浮点计算，当 `space = 0` 时会产生 `-Infinity`，截断为 int 后行为未定义（实际得到 `Integer.MIN_VALUE`）。
3. 应统一使用整数位运算实现 `_ilog2`，与 Linux 内核的 `ilog2` 宏一致。

**修复**: 删除无用变量 `i`；将 `ilog2(space)` 替换为 `_ilog2(space)`。

---

## 二、设计偏差（有意简化，非 bug）

以下偏差是针对 TUN 代理场景的合理简化，已知与 Linux 内核行为不同。

### D-1: TIME_WAIT 跳过 2MSL 等待（`TcpDemultiplexer.java:529`）

Linux 内核在 TIME_WAIT 状态等待 2*MSL（默认 60 秒），防止旧连接迟到重复报文被新连接误收。当前实现立即关闭连接：

```java
if (TCP_TIME_WAIT.equals(state)) {
    tp.state(TCP_CLOSE);
    tcp_done(tp);   // 立即销毁
}
```

在 TUN 代理场景下，每条连接由独立的 4 元组标识，迟到重复报文概率极低，此简化可接受。

### D-2: `tcp_parse_options` 仅解析 MSS 和窗口缩放（`TcpHandshaker.java:295`）

当前只解析 SYN 包中的 MSS 和窗口缩放选项（WScale），未实现：
- SACK Permitted 选项（RFC 2018）
- 时间戳选项（RFC 7323）——`saw_tstmap` 被置 0 但未实际解析
- TCP Fast Open（RFC 7413）

代理场景下仅需基本连通性，此简化合理。

### D-3: `tcp_rcv_established` 为空桩（`TcpInput.java:1619`）

`tcp_rcv_established`（ESTABLISHED 状态快速路径）的所有逻辑均被注释，实际由 `tcp_rcv_state_process` 慢路径处理。功能正确，但失去了 Linux 内核快速路径的性能优化。

### D-4: PAWS（RFC 7323）时间戳防回绕保护未实现（`TcpInput.java:1491`）

`tcp_disordered_ack_check` 被注释，`reason` 恒为 0，导致 `tcp_validate_incoming` 中的 PAWS 检查分支为死代码。由于本实现也不生成时间戳选项，不会产生误判，但缺少对时间戳伪造攻击的防护。

### D-5: 拥塞控制为桩（`TcpOutput.java`，`TcpInput.java`）

以下拥塞控制相关方法均为空实现或固定返回：
- `tcp_cwnd_validate`、`tcp_in_cwnd_reduction`（拥塞窗口验证）
- `tcp_enter_loss`（进入丢失恢复）
- `tcp_mtu_probe`（MTU 探测，返回 -1）
- `tso_fragment`、`tcp_tso_should_defer`（TSO 分段）

适合代理场景下的简单吞吐，不适用于精确拥塞控制需求。

### D-6: `SysctlOptions` 中多个参数未初始化（值为 0）

`ipv4_sysctl_tcp_rmem_2`、`sysctl_rmem_max`、`ipv4_sysctl_tcp_min_snd_mss` 默认值为 0。在 `tcp_select_initial_window` 和 `__tcp_mtu_to_mss` 中使用时，0 值产生的效果等价于"无约束"，不影响正确性，但与实际系统参数不对应。

---

## 三、已验证正确的实现

以下关键逻辑经过逐行对照验证，与 Linux 内核行为一致：

| 模块 | 方法 | 验证结论 |
|------|------|---------|
| `Tcp4Demultiplexer` | `tcp_v4_send_reset` | RST 报文的序列号/ACK号生成逻辑正确，端口/IP 互换正确 |
| `Tcp4Demultiplexer` | `buildIp4Packet` | IPv4 头部与 TCP 头部构建正确，伪头部 TCP 校验和计算正确 |
| `TcpUtils` | `before/after/between` | 32位有符号减法序列号比较，正确处理回绕 |
| `TcpUtils` | `determineEndSeq` | SYN/FIN 各占 1 序列号，payload 累加，计算正确 |
| `TcpInput` | `tcp_rtt_estimator` | Van Jacobson SRTT/RTTVAR 算法（SIGCOMM '88）实现正确 |
| `TcpInput` | `tcp_ack_update_window` | 发送窗口更新（`tcp_may_update_window` 三条件）正确 |
| `TcpInput` | `tcp_sequence` | 接收窗口序列号有效性检查逻辑等价于内核实现 |
| `TcpInput` | `tcp_data_queue_ofo` | OFO 队列的前驱重叠裁剪、后继覆盖驱逐逻辑正确 |
| `TcpInput` | `tcp_ofo_queue` | OFO 队列顺序交付（含 FIN 传播）逻辑正确 |
| `TcpOutput` | `tcp_select_window` | 接收窗口选择、收缩防护、窗口缩放应用正确 |
| `TcpOutput` | `tcp_make_synack` | SYN-ACK 构建（序列号=snt_isn，ACK=rcv_nxt）正确 |
| `TcpDemultiplexer` | `tcp_close_state` / `NEW_STATE` | 状态转移表与 Linux 一致，TCP_ACTION_FIN 位掩码无冲突 |
| `TcpDemultiplexer` | `tcp_create_openreq_child` | 子 socket 初始化（rcv_nxt、snd_una、窗口缩放参数）正确 |
| `TcpHandshaker` | 三次握手流程 | SYN → 建立后端连接 → SYN-ACK → ACK 顺序正确，半连接队列管理正确 |
| `TcpTimer` | 重传/延迟ACK/探针定时器 | 定时器调度与取消逻辑正确，基于 Netty EventLoop 实现 |
| `TcpSock` | `out_of_order_queue` 比较器 | `(a, b) -> a - b` 等价于有符号序列号比较，正确处理回绕 |

---

## 四、总结

| 类别 | 数量 | 严重程度 |
|------|------|---------|
| 确认 BUG | 4 | 中~高 |
| 设计偏差 | 6 | 低（有意简化） |
| 已验证正确 | 15+ | — |

**最高优先级修复**: BUG-2（`tcp_reset_check` `|` 改 `&`），该 bug 存在安全影响，可能导致 ESTABLISHED 状态连接被伪造 RST 注入。

**次优先级**: BUG-1（`SHUTDOWN_MASK = 3`），影响连接关闭阶段状态一致性。
