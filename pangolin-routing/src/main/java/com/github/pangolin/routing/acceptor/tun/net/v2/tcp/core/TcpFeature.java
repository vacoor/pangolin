package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

/**
 * 可选 TCP 特性的统一接入钩子(SPI 骨架)。
 *
 * <p>对齐 Linux 内核 {@code struct tcp_congestion_ops} / {@code tcp_request_sock_ops} /
 * {@code tcp_ulp_ops} 一类的函数表思路,但聚焦在 v2 的"选项协商 + 段拦截"轴线:
 * 把 SACK / Timestamps / WScale / PMTUD / ECN / TFO / 等可选特性从 {@code core}
 * 主路径解耦,通过该接口在 {@code ext/<feature>/} 下注册接入。
 *
 * <p>本接口仅定义契约,<b>本提交不接通主路径</b> — {@link TcpMultiplexer} 暂不新增
 * {@code List<TcpFeature>} 装配链,现有 TS / WS / SACK 仍走常量分支;装配链与回调
 * 插桩留到后续专项 phase。
 *
 * <p>实现约束(见 {@code tcp.java.packages.md} §6):
 * <ol>
 *   <li>实现类位于 {@code v2.tcp.ext.<feature>} 下;</li>
 *   <li>仅依赖 {@code core/} 与 JDK/第三方,不跨 {@code ext/} 兄弟包;</li>
 *   <li>是否装配由 {@link TcpConfig} 开关决定(如 {@code sackEnabled()})。</li>
 * </ol>
 *
 * <p>同一特性可同时实现多条 SPI(例如 SACK 同时是 {@code TcpFeature} 与
 * {@link LossRecovery}),各接口职责正交。
 */
public interface TcpFeature {

    /**
     * 主动发起连接前,往 SYN 段追加本特性所需的选项 / 标志位。
     *
     * <p>对齐 Linux {@code tcp_syn_options}(tcp_output.c)。对 v2 仅做监听端(被动
     * 开连接)而言,本钩子仅在未来支持主动 connect 时才会触发。
     *
     * @param sk  即将发送 SYN 的套接字
     * @param skb SYN 段控制块(调用方已设置 {@code SYN} 位)
     */
    default void onSynSend(TcpSock sk, TcpSkb skb) {
        // default no-op
    }

    /**
     * 被动端收到 SYN 时解析对端带来的选项,决定是否协商成功。
     *
     * <p>对齐 Linux {@code tcp_parse_options} + {@code openreqInit}(tcp_input.c)
     * 的选项落库过程。实现应把协商结果写入 {@link TcpOptionsReceived} 或等价字段,
     * 供 {@link #onSynSend} 的 SYN-ACK 构造阶段使用。
     *
     * @param req  半连接态 {@link TcpRequestSock}
     * @param skb  入站 SYN 段
     * @param opts 已解析的选项视图
     */
    default void onSynReceive(TcpRequestSock req, TcpSkb skb, TcpOptionsReceived opts) {
        // default no-op
    }

    /**
     * ESTABLISHED 态每收到一段数据/ACK 时的通用钩子。
     *
     * <p>对齐 Linux {@code tcp_ack} / {@code dataQueue} 中的选项旁路 —
     * 例如 Timestamps PAWS 校验、SACK 块 ingest、ECN CE-bit 反馈。
     * 实现<b>不得</b>在此修改段序号或有效载荷,仅做"观察 + 更新内部状态"。
     *
     * @param sk   已建立连接的套接字
     * @param skb  入站段控制块
     * @param opts 已解析的选项视图
     */
    default void onSegmentReceive(TcpSock sk, TcpSkb skb, TcpOptionsReceived opts) {
        // default no-op
    }

    /**
     * 构造出站段时装饰额外选项 / 标志位。
     *
     * <p>对齐 Linux {@code tcp_established_options} + {@code tcp_options_write}
     * (tcp_output.c):发送前允许特性往段头追加 TS / SACK-permitted / SACK blocks /
     * MSS 等选项;也允许调整 {@code tcpFlags}(如 ECN CWR/ECE)。
     *
     * <p>实现必须保证追加后选项区不超过 40 字节上限。
     *
     * @param sk  出站套接字
     * @param skb 出站段控制块
     */
    default void decorateOutgoing(TcpSock sk, TcpSkb skb) {
        // default no-op
    }
}
