1. 所有解答使用简体中文
2. 只调整 pangolin-routing 中代码
3. com.github.pangolin.routing.acceptor.tun.net.handler.tcp 为v1版本的TCP实现
4. com.github.pangolin.routing.acceptor.tun.net.v2.tcp 为v2版本的TCP实现
5. 严格按照 Linux 内核中 TCP 实现来调整, 无法对齐或需要调整部分确认后再做
6. Linux 源码参考 https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c
   https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c
