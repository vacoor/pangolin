package com.github.pangolin.proxy.backhaul.server;

import com.github.pangolin.proxy.backhaul.server.shell.ConsoleReaderFactory;
import com.github.pangolin.proxy.backhaul.server.shell.Shell;
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
import java.util.stream.Collectors;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230831
 */
@Slf4j
public class ConsoleHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final Discover discover;
    private final Forwarder forwarder;

    private HeadlessTerminal terminal;
    private OutputStream toConsoleIn;

    public ConsoleHandler(final Discover discover, final Forwarder forwarder) {
        this.discover = discover;
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
                    () -> discover.getAgents().stream().map(Discover.Agent::getId).collect(Collectors.toList())
            );

            this.terminal = terminal;
            this.toConsoleIn = toConsoleIn;
            Shell.create(console, true, discover, null).start();
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
                delegateCtx.write(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b, off, len))).sync();
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
                delegateCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).sync();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e.getMessage());
            }
        }

        @Override
        public void close() {
            delegateCtx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }

    }

    private static class HeadlessTerminal extends TerminalSupport {
        private int cols;
        private int rows;

        HeadlessTerminal() {
            this(true, false, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }

        HeadlessTerminal(boolean ansiSupported, boolean echoEnabled, final int cols, final int rows) {
            super(false);
            setAnsiSupported(ansiSupported);
            setEchoEnabled(echoEnabled);
            this.cols = cols;
            this.rows = rows;
        }

        @Override
        public void init() {
            setEchoEnabled(false);
            setAnsiSupported(true);
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

}
