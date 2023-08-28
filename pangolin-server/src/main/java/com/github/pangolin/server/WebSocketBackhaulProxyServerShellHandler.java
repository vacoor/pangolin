package com.github.pangolin.server;

import com.github.pangolin.server.shell.ConsoleLineReader;
import com.github.pangolin.server.shell.LineReader;
import com.github.pangolin.server.shell.WebSocketBackhaulProxyServerShell;
import com.github.pangolin.server.shell.ShellTerm;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230828
 */
@Slf4j
public class WebSocketBackhaulProxyServerShellHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final WebSocketBackhaulProxyServer server;

    public WebSocketBackhaulProxyServerShellHandler(final WebSocketBackhaulProxyServer server) {
        this.server = server;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext webSocketTunnelContext, final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            webSocketTunnelContext.channel().config().setAutoRead(false);
            webSocketTunnelContext.pipeline().remove(webSocketTunnelContext.handler());

            final PipedOutputStream out = new PipedOutputStream();
            final PipedInputStream innerIn = new PipedInputStream(out);
            final OutputStream innerOut = new WebSocketBinaryOutputStream(webSocketTunnelContext);
            final ShellTerm terminal = new ShellTerm();
            final LineReader reader = new ConsoleLineReader(server, innerIn, innerOut, terminal);
            new WebSocketBackhaulProxyServerShell(server, reader, new PrintStream(innerOut), null).start();

            webSocketTunnelContext.pipeline().addLast(new SimpleChannelInboundHandler<WebSocketFrame>() {
                @Override
                protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
                    if (msg instanceof BinaryWebSocketFrame) {
                        out.write(ByteBufUtil.getBytes(msg.content()));
                        out.flush();
                    } else if (msg instanceof TextWebSocketFrame) {
                        final String message = ((TextWebSocketFrame) msg).text();
                        final int index = message.indexOf(' ');
                        final String command = -1 < index ? message.substring(0, index) : message;
                        final String commandArgs = -1 < index ? message.substring(index + 1) : "";
                        if ("\u0009\u0011".equals(command)) {
                            final String[] dimension = commandArgs.split("x", 2);
                            try {
                                final int cols = Integer.parseInt(dimension[0]);
                                final int rows = Integer.parseInt(dimension[1]);
                                terminal.setCols(cols);
                                terminal.setRows(rows);
                            } catch (final NumberFormatException ignore) {
                                log.error("Execute command '{}' error", message, ignore);
                            }
                            return;
                        }
                    }
                }
            });

            webSocketTunnelContext.channel().config().setAutoRead(true);
        }
        super.userEventTriggered(webSocketTunnelContext, evt);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame msg) throws Exception {
        ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
    }

    class WebSocketBinaryOutputStream extends OutputStream {
        private final ChannelHandlerContext webSocketContext;

        WebSocketBinaryOutputStream(final ChannelHandlerContext webSocketContext) {
            this.webSocketContext = webSocketContext;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            try {
                /*-
                 * await: 不等待多线程写入时会丢失数据或多次发送相同数据.
                 */
                webSocketContext.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b, off, len))).await();
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
                webSocketContext.writeAndFlush(Unpooled.EMPTY_BUFFER).sync();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e.getMessage());
            }
        }

        @Override
        public void close() {
            webSocketContext.close();
        }
    }
}
