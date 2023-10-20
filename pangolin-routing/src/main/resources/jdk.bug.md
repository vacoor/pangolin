JDK Proxy 使用 SOCKS5时未正确处理响应, 直到 jdk 9b02 / openjdk 8u222 才修复.
BUG 见: https://bugs.java.com/bugdatabase/view_bug.do?bug_id=7100957
```java.net.SocksSocketImpl.connect```
```java
case DOMAIN_NAME:
    /*-
      这里域名长度应该是下一个字节
      byte[] lenBuf = new byte[1];
      i = readSocksReply(in, lenBuf, deadlineMillis);
      if (i != 1)
        throw new SocketException("Reply from SOCKS server badly formatted");
      len = lenBuf[0];
     */
    len = data[1];
    byte[] host = new byte[len];
    i = readSocksReply(in, host, deadlineMillis);
    if (i != len)
        throw new SocketException("Reply from SOCKS server badly formatted");
    data = new byte[2];
    i = readSocksReply(in, data, deadlineMillis);
    if (i != 2)
        throw new SocketException("Reply from SOCKS server badly formatted");
    break;
case IPV6:
    /*-
      这里长度应该固定16
      len = 16;
     */
    len = data[1];
    addr = new byte[len];
    i = readSocksReply(in, addr, deadlineMillis);
    if (i != len)
        throw new SocketException("Reply from SOCKS server badly formatted");
    data = new byte[2];
    i = readSocksReply(in, data, deadlineMillis);
    if (i != 2)
        throw new SocketException("Reply from SOCKS server badly formatted");
    break;
```