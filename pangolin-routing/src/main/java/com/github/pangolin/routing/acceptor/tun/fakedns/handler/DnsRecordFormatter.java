package com.github.pangolin.routing.acceptor.tun.fakedns.handler;

import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.buffer.ByteBuf;

public class DnsRecordFormatter {
    
    public static String formatDnsRecord(DnsRecord record) {
        StringBuilder sb = new StringBuilder();
        
        // 添加记录类型
        DnsRecordType type = record.type();
        sb.append(type.name()).append(" ");
        
        // 根据记录类型处理内容
        if (type.equals(DnsRecordType.A)) {
            sb.append(formatARecord((DnsRawRecord) record));
        } else if (type.equals(DnsRecordType.AAAA)) {
            sb.append(formatAAAARecord((DnsRawRecord) record));
        } else if (type.equals(DnsRecordType.CNAME)) {
            sb.append(formatCNAMERecord((DnsRawRecord) record));
        } else if (type.equals(DnsRecordType.MX)) {
            sb.append(formatMXRecord((DnsRawRecord) record));
        } else if (type.equals(DnsRecordType.TXT)) {
            sb.append(formatTXTRecord((DnsRawRecord) record));
        } else if (type.equals(DnsRecordType.NS)) {
            sb.append(formatNSRecord((DnsRawRecord) record));
        } else if (type.equals(DnsRecordType.PTR)) {
            sb.append(formatPTRRecord((DnsRawRecord) record));
        } else if (type.equals(DnsRecordType.SRV)) {
            sb.append(formatSRVRecord((DnsRawRecord) record));
        } else {
            sb.append(formatGenericRecord((DnsRawRecord) record));
        }
        
        // 添加TTL
        sb.append(" ").append(record.timeToLive()).append("s");
        
        return sb.toString();
    }
    
    private static String formatARecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null && content.readableBytes() >= 4) {
            try {
                int ip = content.readInt();
                return String.format("%d.%d.%d.%d", 
                    (ip >> 24) & 0xFF,
                    (ip >> 16) & 0xFF,
                    (ip >> 8) & 0xFF,
                    ip & 0xFF);
            } catch (Exception e) {
                return "Invalid IP";
            }
        }
        return "Unknown";
    }
    
    private static String formatAAAARecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null && content.readableBytes() >= 16) {
            try {
                // 简化处理，返回十六进制表示
                byte[] bytes = new byte[16];
                content.readBytes(bytes);
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < bytes.length; i++) {
                    hex.append(String.format("%02x", bytes[i]));
                    if (i % 2 == 1 && i < bytes.length - 1) hex.append(":");
                }
                return hex.toString();
            } catch (Exception e) {
                return "Invalid IPv6";
            }
        }
        return "Unknown";
    }
    
    private static String formatCNAMERecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null) {
            try {
                // 简化处理，返回内容字符串
                return content.toString(io.netty.util.CharsetUtil.UTF_8);
            } catch (Exception e) {
                return "Invalid CNAME";
            }
        }
        return "Unknown";
    }
    
    private static String formatMXRecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null && content.readableBytes() >= 2) {
            try {
                // 读取优先级（2字节）
                int priority = content.readUnsignedShort();
                // 剩余内容为域名
                String domain = content.toString(io.netty.util.CharsetUtil.UTF_8);
                return String.format("%d %s", priority, domain);
            } catch (Exception e) {
                return "Invalid MX";
            }
        }
        return "Unknown";
    }
    
    private static String formatTXTRecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null) {
            try {
                // TXT记录通常包含长度前缀
                StringBuilder txt = new StringBuilder();
                while (content.isReadable()) {
                    int length = content.readUnsignedByte();
                    if (length > 0) {
                        byte[] data = new byte[length];
                        content.readBytes(data);
                        txt.append(new String(data, io.netty.util.CharsetUtil.UTF_8));
                    }
                }
                return txt.toString();
            } catch (Exception e) {
                return "Invalid TXT";
            }
        }
        return "Unknown";
    }
    
    private static String formatNSRecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null) {
            try {
                return content.toString(io.netty.util.CharsetUtil.UTF_8);
            } catch (Exception e) {
                return "Invalid NS";
            }
        }
        return "Unknown";
    }
    
    private static String formatPTRRecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null) {
            try {
                return content.toString(io.netty.util.CharsetUtil.UTF_8);
            } catch (Exception e) {
                return "Invalid PTR";
            }
        }
        return "Unknown";
    }
    
    private static String formatSRVRecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null && content.readableBytes() >= 6) {
            try {
                int priority = content.readUnsignedShort();
                int weight = content.readUnsignedShort();
                int port = content.readUnsignedShort();
                String target = content.toString(io.netty.util.CharsetUtil.UTF_8);
                return String.format("%d %d %d %s", priority, weight, port, target);
            } catch (Exception e) {
                return "Invalid SRV";
            }
        }
        return "Unknown";
    }
    
    private static String formatGenericRecord(DnsRawRecord record) {
        ByteBuf content = record.content();
        if (content != null) {
            try {
                return content.toString(io.netty.util.CharsetUtil.UTF_8);
            } catch (Exception e) {
                return "Unknown";
            }
        }
        return "Unknown";
    }
    
    public static void main(String[] args) {
        // 示例用法
        System.out.println("A记录示例: A 192.168.1.1 30s");
        System.out.println("CNAME记录示例: CNAME example.com 60s");
        System.out.println("MX记录示例: MX 10 mail.example.com 300s");
        System.out.println("TXT记录示例: TXT \"v=spf1 include:_spf.google.com ~all\" 3600s");
    }
}
