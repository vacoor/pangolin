package com.github.pangolin.routing.proxy.beta;

import com.jcraft.jsch.*;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ConnectionPendingException;

public class SshProxyHandler extends ChannelDuplexHandler {
    private final InetSocketAddress sa;
    private final String username;
    private final String password;

    private volatile Session session;
    private InetSocketAddress destinationAddress;
    private SshForwardTunnel tunnel;

    private volatile ChannelDirectTCPIP channel;

    private volatile int forwardPort;

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
            /*-
             * XXX 这种方式没有办法实现代理链.
             */
            destinationAddress = (InetSocketAddress) remoteAddress;
            try {
                tunnel = new SshForwardTunnel(sa.getHostString(), sa.getPort(), username, password);
                forwardPort = tunnel.open(destinationAddress.getHostString(), destinationAddress.getPort());

                ctx.connect(new InetSocketAddress("127.0.0.1", forwardPort), localAddress, promise);
            } catch (Exception e) {
                promise.tryFailure(e);
            }
        }
    }

    @Override
    public void disconnect(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
        super.disconnect(ctx, promise);
        if (0 < forwardPort) {
            tunnel.close(forwardPort);
        }
        tunnel.shutdownGracefully();
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
}