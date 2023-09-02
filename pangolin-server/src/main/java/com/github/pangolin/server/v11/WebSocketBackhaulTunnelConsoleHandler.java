package com.github.pangolin.server.v11;

import com.github.pangolin.server.v11.shell.ConsoleReaderFactory;
import com.github.pangolin.server.v11.shell.Shell;
import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import jline.TerminalSupport;
import jline.console.ConsoleReader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.stream.Collectors;

/**
 */
@Slf4j
public class WebSocketBackhaulTunnelConsoleHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine;
    private final WebSocketBackhaulTunnelForwarder forwarder;

    private HeadlessTerminal terminal;
    private OutputStream toConsoleIn;

    public WebSocketBackhaulTunnelConsoleHandler(final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine, final WebSocketBackhaulTunnelForwarder forwarder) {
        this.webSocketBackhaulTunnelEngine = webSocketBackhaulTunnelEngine;
        this.forwarder = forwarder;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof HandshakeComplete) {
            final HeadlessTerminal terminal = new HeadlessTerminal();
            final PipedOutputStream toConsoleIn = new PipedOutputStream();
            final ConsoleReader console = ConsoleReaderFactory.newConsoleReader(
                    new PipedInputStream(toConsoleIn),
                    new WebSocketBinaryOutput(ctx),
                    terminal,
                    () -> webSocketBackhaulTunnelEngine.getAgents().stream().map(WebSocketBackhaulTunnelEngine.Agent::getId).collect(Collectors.toList())
            );

            this.terminal = terminal;
            this.toConsoleIn = toConsoleIn;
            Shell.create(console, true, webSocketBackhaulTunnelEngine, forwarder).start();
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            final String text = ((TextWebSocketFrame) frame).text();
            final int index = text.indexOf(' ');
            final String command = -1 < index ? text.substring(0, index) : text;
            final String commandArgs = -1 < index ? text.substring(index + 1) : "";
            if ("\u0009\u0011".equals(command)) {
                final String[] dimension = commandArgs.split("x", 2);
                try {
                    terminal.cols = Integer.parseInt(dimension[0]);
                    terminal.rows = Integer.parseInt(dimension[1]);
                } catch (final NumberFormatException ignore) {
                    log.error("Execute command '{}' error", text, ignore);
                }
            } else {
                writeAndFlush(toConsoleIn, ByteBufUtil.getBytes(frame.content()));
            }
        } else if (frame instanceof BinaryWebSocketFrame) {
            writeAndFlush(toConsoleIn, ByteBufUtil.getBytes(frame.content()));
        }
    }

    private void writeAndFlush(final OutputStream out, final byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    private static class WebSocketBinaryOutput extends OutputStream {
        private final ChannelHandlerContext delegateCtx;

        WebSocketBinaryOutput(final ChannelHandlerContext delegateCtx) {
            this.delegateCtx = delegateCtx;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            try {
                /*-
                 * await: 不等待多线程写入时会丢失数据或多次发送相同数据.
                 */
                if (delegateCtx.channel().isActive()) {
                    // delegateCtx.write(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b, off, len))).sync();
                    delegateCtx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b, off, len))).sync();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e.getMessage());
            }
        }

        @Override
        public void write(final int b) throws IOException {
            this.write(new byte[]{(byte) b});
        }

        @Override
        public void flush() throws IOException {
            try {
                if (delegateCtx.channel().isActive()) {
                    delegateCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).sync();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e.getMessage());
            }
        }

        @Override
        public void close() {
            if (delegateCtx.channel().isActive()) {
                delegateCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }

    }

    private static class HeadlessTerminal extends TerminalSupport {
        private int cols;
        private int rows;

        HeadlessTerminal() {
            this(true, false, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }

        HeadlessTerminal(boolean ansiSupported, boolean echoEnabled, final int cols, final int rows) {
            super(true);
            setAnsiSupported(ansiSupported);
            setEchoEnabled(echoEnabled);
            this.cols = cols;
            this.rows = rows;
        }

        @Override
        public int getWidth() {
            return cols;
        }

        @Override
        public int getHeight() {
            return rows;
        }

        @Override
        public String getOutputEncoding() {
            return "UTF-8";
        }

        @Override
        public boolean hasWeirdWrap() {
            return true;
        }
    }

    public static void main(String[] args) throws InterruptedException {

        final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine = new WebSocketBackhaulTunnelEngine();
        final NioEventLoopGroup bossGroup = new NioEventLoopGroup(2);
        final NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        final WebSocketBackhaulTunnelForwarder forwarder = new WebSocketBackhaulTunnelForwarder(webSocketBackhaulTunnelEngine, bossGroup, workerGroup);
        Channels.listen(null, 10443, new NioEventLoopGroup(), new NioEventLoopGroup(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
//                if (null != sslContext) {
//                    pipeline.addLast(sslContext.newHandler(ch.alloc()));
//                }
                pipeline.addLast(
                        new HttpServerCodec(),
                        new HttpObjectAggregator(8 * 1024 * 1024),
                        /*- 浏览器似乎处理压缩有问题(permessage-deflate).
                        new WebSocketServerCompressionHandler(),
                        new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, true, 65536, true, true),
                        */
                        new WebSocketServerProtocolHandler("", "*", true, 65536, true, true),
                        // new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS),
                        new WebSocketBackhaulTunnelConsoleHandler(webSocketBackhaulTunnelEngine, forwarder)
                );
            }
        }).sync().channel().closeFuture().sync();
    }
}
