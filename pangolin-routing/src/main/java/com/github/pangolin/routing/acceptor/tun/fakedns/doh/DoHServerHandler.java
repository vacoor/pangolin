package com.github.pangolin.routing.acceptor.tun.fakedns.doh;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.dns.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Base64;

public class DoHServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        if (!isValidDoHRequest(request)) {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid DoH request");
            return;
        }
        
        try {
            byte[] dnsQuery = extractDnsQuery(request);
            byte[] dnsResponse = processDnsQuery(dnsQuery, ctx.channel().localAddress());
            sendDoHResponse(ctx, request, dnsResponse);
        } catch (Exception e) {
            sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "DNS processing error");
        }
    }
    
    private boolean isValidDoHRequest(FullHttpRequest request) {
        String method = request.method().name();
        if ("GET".equals(method)) {
            QueryStringDecoder queryDecoder = new QueryStringDecoder(request.uri());
            return queryDecoder.parameters().containsKey("dns");
        } else if ("POST".equals(method)) {
            String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
            return "application/dns-message".equals(contentType);
        }
        return false;
    }
    
    private byte[] extractDnsQuery(FullHttpRequest request) {
        String method = request.method().name();
        if ("GET".equals(method)) {
            QueryStringDecoder queryDecoder = new QueryStringDecoder(request.uri());
            String dnsParam = queryDecoder.parameters().get("dns").get(0);
            return Base64.getUrlDecoder().decode(dnsParam);
        } else {
            ByteBuf content = request.content();
            byte[] dnsData = new byte[content.readableBytes()];
            content.readBytes(dnsData);
            return dnsData;
        }
    }

    private final DnsRecordDecoder recordDecoder = DnsRecordDecoder.DEFAULT;
    private final DnsRecordEncoder recordEncoder = DnsRecordEncoder.DEFAULT;

    private byte[] processDnsQuery(byte[] dnsQuery, SocketAddress socketAddress) throws Exception {
        // 这里实现DNS查询处理逻辑
        // 可以转发到上游DNS服务器或使用本地DNS解析
        ByteBuf buf = Unpooled.wrappedBuffer(dnsQuery);
        final int id = buf.readUnsignedShort();

        final int flags = buf.readUnsignedShort();
        if (flags >> 15 == 1) {
            throw new CorruptedFrameException("not a query");
        }

        final DnsQuery query = new DefaultDnsQuery(id, DnsOpCode.valueOf((byte) (flags >> 11 & 0xf)));
        query.setRecursionDesired((flags >> 8 & 1) == 1);
        query.setZ(flags >> 4 & 0x7);

        final int questionCount = buf.readUnsignedShort();
        final int answerCount = buf.readUnsignedShort();
        final int authorityRecordCount = buf.readUnsignedShort();
        final int additionalRecordCount = buf.readUnsignedShort();

        decodeQuestions(query, buf, questionCount);
        decodeRecords(query, DnsSection.ANSWER, buf, answerCount);
        decodeRecords(query, DnsSection.AUTHORITY, buf, authorityRecordCount);
        decodeRecords(query, DnsSection.ADDITIONAL, buf, additionalRecordCount);

        DnsRecord question = query.recordAt(DnsSection.QUESTION);
        final DefaultDnsRawRecord dnsAnswer = new DefaultDnsRawRecord(
                question.name(), DnsRecordType.A, 10,
                Unpooled.wrappedBuffer(InetAddress.getByName("192.168.1.1").getAddress())
        );
        DnsResponse response = new DefaultDnsResponse(query.id());
        response.addRecord(DnsSection.QUESTION, question);
        response.addRecord(DnsSection.ANSWER, dnsAnswer);
        response.setRecursionAvailable(true);
        response.setAuthoritativeAnswer(true);


        // return dnsQuery; // 示例返回，实际需要真实DNS解析
        return response(response);
    }

    private byte[] response(final DnsResponse response) throws Exception {
        final ByteBuf buf = Unpooled.buffer(1024);

        boolean success = false;
        try {
            encodeHeader(response, buf);
            encodeQuestions(response, buf);
            encodeRecords(response, DnsSection.ANSWER, buf);
            encodeRecords(response, DnsSection.AUTHORITY, buf);
            encodeRecords(response, DnsSection.ADDITIONAL, buf);
            success = true;
        } finally {
            if (!success) {
                buf.release();
            }
        }
        return ByteBufUtil.getBytes(buf);
    }

    /**
     * Encodes the header that is always 12 bytes long.
     *
     * @param response the response header being encoded
     * @param buf      the buffer the encoded data should be written to
     */
    private static void encodeHeader(DnsResponse response, ByteBuf buf) {
        buf.writeShort(response.id());
        int flags = 32768;
        flags |= (response.opCode().byteValue() & 0xFF) << 11;
        if (response.isAuthoritativeAnswer()) {
            flags |= 1 << 10;
        }
        if (response.isTruncated()) {
            flags |= 1 << 9;
        }
        if (response.isRecursionDesired()) {
            flags |= 1 << 8;
        }
        if (response.isRecursionAvailable()) {
            flags |= 1 << 7;
        }
        flags |= response.z() << 4;
        flags |= response.code().intValue();
        buf.writeShort(flags);
        buf.writeShort(response.count(DnsSection.QUESTION));
        buf.writeShort(response.count(DnsSection.ANSWER));
        buf.writeShort(response.count(DnsSection.AUTHORITY));
        buf.writeShort(response.count(DnsSection.ADDITIONAL));
    }

    private void encodeQuestions(DnsResponse response, ByteBuf buf) throws Exception {
        final int count = response.count(DnsSection.QUESTION);
        for (int i = 0; i < count; i++) {
            recordEncoder.encodeQuestion(response.recordAt(DnsSection.QUESTION, i), buf);
        }
    }

    private void encodeRecords(DnsResponse response, DnsSection section, ByteBuf buf) throws Exception {
        final int count = response.count(section);
        for (int i = 0; i < count; i++) {
            recordEncoder.encodeRecord(response.recordAt(section, i), buf);
        }
    }

    private void decodeQuestions(DnsQuery query, ByteBuf buf, int questionCount) throws Exception {
        for (int i = questionCount; i > 0; i--) {
            query.addRecord(DnsSection.QUESTION, recordDecoder.decodeQuestion(buf));
        }
    }

    private void decodeRecords(
            DnsQuery query, DnsSection section, ByteBuf buf, int count) throws Exception {
        for (int i = count; i > 0; i--) {
            final DnsRecord r = recordDecoder.decodeRecord(buf);
            if (r == null) {
                // Truncated response
                break;
            }

            query.addRecord(section, r);
        }
    }
    
    private void sendDoHResponse(ChannelHandlerContext ctx, FullHttpRequest request, byte[] dnsResponse) {
        FullHttpResponse response;
        
        if ("GET".equals(request.method().name())) {
            String base64Response = Base64.getUrlEncoder().encodeToString(dnsResponse);
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/dns-message");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, dnsResponse.length);
            response.content().writeBytes(dnsResponse);
        } else {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/dns-message");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, dnsResponse.length);
            response.content().writeBytes(dnsResponse);
        }
        
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=3600");
        ctx.writeAndFlush(response);
    }
    
    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ByteBuf content = Unpooled.copiedBuffer(message, CharsetUtil.UTF_8);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.content().writeBytes(content);
        ctx.writeAndFlush(response);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }


}
