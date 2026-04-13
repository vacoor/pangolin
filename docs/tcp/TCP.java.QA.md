针对重构后的 netty TCP 协议栈位于 com.github.pangolin.routing.acceptor.tun.net.v2.tcp
参考 Linux 内核 TCP 协议栈实现
存在问题:

1. com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshaker#L181 行问题
  a. 发送RST时使用的是TCP连接的channel, 写入完成 IP 数据是否可行
  b. 发送完RST后,连接已中断, 是否应该清理并释放资源? 
     看上去 com.github.pangolin.routing.acceptor.tun.net.v2.tcp.handshake.TcpHandshakeHandler 关闭时会取消握手重传定时器, 
   com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpConnectionChannel 会从注册表中清理,但是都没有触发

2. 类似1,对于连接建立阶段 com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established 和关闭阶段 com.github.pangolin.routing.acceptor.tun.net.v2.tcp.close 
   也需要对资源释放做类似检查

3. com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpEstablishedHandler
   a. close 被定义为主动关闭,但是在 #L101 以及 exceptionCaught 中RST时却调用了 close
   b. 需要确认 com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpEstablishedHandler.channelInactive实现是否正确? 它直接调用了 conn.close()
   c. channelRead 中对数据处理的步骤和内容显然和com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpInput.tcp_rcv_state_process不同
      尤其是: com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpSegmentValidator.validate 和 com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpInput.tcp_validate_incoming 以及
   com.github.pangolin.routing.acceptor.tun.net.v2.tcp.established.TcpAckHandler.onAck和com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpInput.tcp_ack

4. 对于2,3中提到的连接建立阶段和关闭阶段, Linux 协议栈中均采用了类似的处理, 校验标识位,序列号等,但是现在关闭阶段的逻辑明显只处理了理想情景下的情况
   参考: com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpInput.tcp_rcv_state_process
   参考: https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#tcp_rcv_state_process

5. com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSegmenter 中似乎缺少了重传定时器