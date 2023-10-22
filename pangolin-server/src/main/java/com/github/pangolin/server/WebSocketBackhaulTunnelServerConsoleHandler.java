package com.github.pangolin.server;

import com.github.pangolin.server.shell.ConsoleReaderFactory;
import com.github.pangolin.server.shell.WebSocketBackhaulTunnelServerShell;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import jline.TerminalSupport;
import jline.console.ConsoleReader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 *
 */
@Slf4j
public class WebSocketBackhaulTunnelServerConsoleHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Pattern RESIZE_PATTERN = Pattern.compile("^\\u001B\\[8;([0-9]+);([0-9])+t$");

    private final WebSocketBackhaulTunnelServerEngine engine;
    private final WebSocketBackhaulTunnelServerForwarder forwarder;

    private HeadlessTerminal terminal;
    private OutputStream toConsoleIn;

    public WebSocketBackhaulTunnelServerConsoleHandler(final WebSocketBackhaulTunnelServerEngine engine, final WebSocketBackhaulTunnelServerForwarder forwarder) {
        this.engine = engine;
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
                    () -> engine.getAgents().stream().map(WebSocketBackhaulTunnelServerEngine.Agent::getId).collect(Collectors.toList())
            );

            this.terminal = terminal;
            this.toConsoleIn = toConsoleIn;
            WebSocketBackhaulTunnelServerShell.create(console, true, engine, forwarder).start();
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            final String text = ((TextWebSocketFrame) frame).text();
            final Matcher matcher = RESIZE_PATTERN.matcher(text);
            if (matcher.find()) {
                terminal.cols = Integer.parseInt(matcher.group(1));
                terminal.rows = Integer.parseInt(matcher.group(2));
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
            return false;
        }
    }

}
