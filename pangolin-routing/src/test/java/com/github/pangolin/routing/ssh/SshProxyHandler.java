package com.github.pangolin.routing.ssh;

import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.server.NettyServer;
import com.jcraft.jsch.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ConnectionPendingException;
import java.util.concurrent.locks.LockSupport;

public class SshProxyHandler extends ChannelDuplexHandler {
    private final InetSocketAddress sa;
    private final String username;
    private final String password;

    private volatile Session session;
    private InetSocketAddress destinationAddress;
    private SshForwardTunnel tunnel;

    private volatile ChannelDirectTCPIP channel;

    public SshProxyHandler(final InetSocketAddress sa, final String username, final String password) {
        this.sa = sa;
        this.username = username;
        this.password = password;
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        if (null != destinationAddress) {
            promise.setFailure(new ConnectionPendingException());
        } else {
            destinationAddress = (InetSocketAddress) remoteAddress;
            try {
                tunnel = new SshForwardTunnel(sa.getHostString(), sa.getPort(), username, password);
                int port = tunnel.open(destinationAddress.getHostString(), destinationAddress.getPort());

                ctx.connect(new InetSocketAddress("127.0.0.1", port), localAddress, promise);
            } catch (Exception e) {
                promise.tryFailure(e);
            }
        }
    }

    /*
    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        ByteBuf m = (ByteBuf) msg;
        final byte[] bytes = new byte[m.readableBytes()];
        m.readBytes(bytes);
        if (null == channel) {
            return;
        }
//        Buffer buf=new Buffer();
//        Packet packet=new Packet(buf);
        channel.getOutputStream().write(bytes);
        channel.getOutputStream().flush();
        ReferenceCountUtil.release(m);
//        super.write(ctx, msg, promise);
    }

    @Override
    public void read(final ChannelHandlerContext ctx) throws Exception {
        if (null == channel) {
            super.read(ctx);
            return;
        }
        final byte[] buf = new byte[1024];
        int read = channel.getInputStream().read(buf);
        if (-1 < read) {
            ByteBuf msg = Unpooled.wrappedBuffer(buf, 0, read);
            ctx.fireChannelRead(msg);
        }
//        ctx.fireChan
//        super.read(ctx);
    }
    */

    private ChannelDirectTCPIP connect(final String host, final int port, final SocketFactory factory) throws Exception {
        ChannelDirectTCPIP channel = (ChannelDirectTCPIP) getSession(factory).openChannel("direct-tcpip");
//        channel.setInputStream(System.in);
//        channel.setOutputStream(System.out);

//        addChannel.invoke(session, channel);
//        session.addChannel(channel);

        channel.setHost(host);
        channel.setPort(port);
//        channel.setOutputStream(new );
//        ((ChannelDirectTCPIP)channel).setHost(host);
//        ((ChannelDirectTCPIP)channel).setPort(rport);
//        ((ChannelDirectTCPIP)channel).setOrgIPAddress(socket.getInetAddress().getHostAddress());
//        ((ChannelDirectTCPIP)channel).setOrgPort(socket.getPort());
//        channel.connect(connectTimeout);
        channel.connect(0);
        return channel;
    }

    private synchronized Session getSession(final SocketFactory factory) {
        try {
            if (null != session && session.isConnected()) {
                return session;
            }
            if (null != session) {
                session.disconnect();
            }
            session = createSession(factory);
            return session;
        } catch (JSchException e) {
            throw new IllegalStateException(e);
        }
    }

    private Session createSession(final SocketFactory factory) throws JSchException {
        final JSch jsch = new JSch();
        final String hostname = sa.getHostString();
        final int port = sa.getPort();
        final Session session = jsch.getSession(username, hostname, port);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("compression.s2c", "zlib,none");
        session.setConfig("compression.c2s", "zlib,none");
        session.setPassword(password);
        session.setDaemonThread(false);

        session.setSocketFactory(factory);

        session.connect();
        return session;
    }

    public static void main(String[] args) throws Exception {

        String hostname = "127.0.0.1";
        int port = 22;
        String username = "";
        String password = "";

        SshProxyHandler handler = new SshProxyHandler(new InetSocketAddress(hostname, port), username, password);
        ChannelDirectTCPIP channel = handler.connect("baidu.com", 80, null);

        channel.setOutputStream(System.out);
//        channel.setInputStream(System.in);
//        channel.connect(0);
        OutputStream out = channel.getOutputStream();
        out.write("GET / HTTP/1.1\r\n\r\n".getBytes());
        out.flush();

        /*
        final InputStream in = channel.getInputStream();
        final byte[] buf = new byte[1024];
        int len;
        while (-1 < (len = in.read(buf))) {
            System.out.println(new String(buf, 0, len));
        }
        */

        System.out.println("OK");
        LockSupport.park();

        System.exit(0);
        /*
        */
        final NettyServer server = new NettyServer(1080);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                AbstractProxySocketChannelFactory factory = new AbstractProxySocketChannelFactory() {

                    @Override
                    protected ChannelHandler select(final SocketAddress destinationAddress) {
                        return new SshProxyHandler(new InetSocketAddress(hostname, port), username, password);
                    }
                };
                Socks5ProxyServerHandler handler = new Socks5ProxyServerHandler(null, null, factory);
//                ch.pipeline().addLast(new MixinServerInitializer(httpHandshaker));
                ch.pipeline().addLast(handler);
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("ProxyServer started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();
    }
}