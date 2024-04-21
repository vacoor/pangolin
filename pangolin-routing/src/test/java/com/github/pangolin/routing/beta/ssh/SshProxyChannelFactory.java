package com.github.pangolin.routing.beta.ssh;

import com.jcraft.jsch.*;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LoggingHandler;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;

public class SshProxyChannelFactory implements ChannelFactory<OioSocketChannel> {
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public SshProxyChannelFactory(final String host, final int port, final String username, final String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override
    public OioSocketChannel newChannel() {
        return new OioSocketChannel(socket()) {
            @Override
            protected void doConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) throws Exception {
                super.doConnect(remoteAddress, localAddress);
            }

        };
    }

    private Socket socket() {
        return new Socket() {
            Channel channel;
            Socket socket;
            InputStream in;
            OutputStream out;

            @Override
            public void setTcpNoDelay(final boolean on) throws SocketException {
                super.setTcpNoDelay(on);
            }

            @Override
            public void connect(final SocketAddress endpoint) throws IOException {
                this.connect(endpoint, 0);
            }

            @Override
            public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
                try {
                    channel = create(endpoint, timeout);
                    final Field f = Session.class.getDeclaredField("socket");
                    f.setAccessible(true);
                    socket = (Socket) f.get(channel.getSession());
                    in = channel.getInputStream();
                    out = new OutputStream() {
                        OutputStream o = channel.getOutputStream();
                        @Override
                        public void write(final int b) throws IOException {
                            this.write(new byte[]{(byte) b});
                        }

                        @Override
                        public void write(final byte[] b, final int off, final int len) throws IOException {
                            o.write(b, off, len);
                            o.flush();
                        }
                    };
                    channel.connect(timeout);
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void sendUrgentData(final int data) throws IOException {
                super.sendUrgentData(data);
            }

            @Override
            public InputStream getInputStream() throws IOException {
//                 return null != channel ? channel.getInputStream() : null;
                return in;
            }

            @Override
            public OutputStream getOutputStream() throws IOException {
//                return null != channel ? channel.getOutputStream() : null;
                return out;
            }

            @Override
            public synchronized void close() throws IOException {
                try {
                    if (null != channel) {
                        channel.disconnect();
                        // FIXME reuse session
                        channel.getSession().disconnect();
                    }
                } catch (final Exception ex) {
                    // ignore
                    ex.printStackTrace();
                }
                super.close();
            }

            @Override
            public boolean isConnected() {
                return null != channel && channel.isConnected();
            }

            @Override
            public boolean isClosed() {
                return null != channel && channel.isClosed();
            }

            @Override
            public InetAddress getInetAddress() {
                return socket.getInetAddress();
            }

            @Override
            public InetAddress getLocalAddress() {
                return socket.getLocalAddress();
            }

            @Override
            public int getPort() {
                return socket.getPort();
            }

            @Override
            public int getLocalPort() {
                return socket.getLocalPort();
            }
        };
    }

    private ChannelDirectTCPIP create(final SocketAddress endpoint, final int timeout) throws Exception {

        Session session = createSession(username, password, host, port, null);
        final InetSocketAddress sa = (InetSocketAddress) endpoint;
//        return this.connect(session, sa.getHostString(), sa.getPort(), timeout);
        ChannelDirectTCPIP c = (ChannelDirectTCPIP) session.getStreamForwarder(sa.getHostString(), sa.getPort());
        return c;
    }

    private ChannelDirectTCPIP connect(final Session session, final String host, final int port, int timeout) throws Exception {
        ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
        channel.setHost(host);
        channel.setPort(port);
        channel.connect(timeout);
        return channel;
    }

    private Session createSession(final String username, final String password, final String hostname, final int port, final SocketFactory factory) throws JSchException {
        final JSch jsch = new JSch();
        final Session session = jsch.getSession(username, hostname, port);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("compression.s2c", "zlib,none");
        session.setConfig("compression.c2s", "zlib,none");
        session.setPassword(password);
        session.setDaemonThread(true);

        session.setSocketFactory(factory);

        session.connect();
        return session;
    }

    public static void main(String[] args) throws Exception {
        final InetSocketAddress sa = new InetSocketAddress("www.baidu.com", 80);
        final String username = "";
        final String password = "";
        final String host = "127.0.0.1";
        final int port = 22;

        SshProxyServer s = new SshProxyServer("S", host, port, username, password);
        s.open(sa, 0, true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new LoggingHandler());
                ch.pipeline().addLast(new HttpClientCodec());
                ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        super.channelActive(ctx);
                        ctx.writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
                        System.out.println("Write");
                    }

                    @Override
                    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
                        super.channelInactive(ctx);
                        System.out.println("Inactive");
                    }

                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpResponse msg) throws Exception {
                        System.out.println(msg);
                    }
                });
            }
        }).channel().closeFuture().sync();
        System.out.println();
    }
}