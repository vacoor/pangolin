package com.github.pangolin.routing.acceptor.tun.fakedns.doh;

import java.nio.ByteBuffer;

public class DnsMessage {
    private final byte[] data;
    
    public DnsMessage(byte[] data) {
        this.data = data;
    }
    
    public byte[] getData() {
        return data;
    }
    
    public String getQueryName() {
        // 解析DNS消息获取查询的域名
        // 这里简化实现，实际需要完整的DNS协议解析
        return "example.com";
    }
    
    public boolean isValid() {
        return data != null && data.length > 12; // DNS头部最小12字节
    }
}