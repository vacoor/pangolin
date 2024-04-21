package com.github.pangolin.routing.ssh;

import com.google.common.collect.Maps;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import freework.util.Throwables;

import java.net.BindException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * @since 20200416
 */
public class SshForwardTunnel {
    private final Session session;
    private final Set<Integer> registeredPorts = new CopyOnWriteArraySet<>();
    private final AtomicInteger registeredCount = new AtomicInteger(0);

    private static final Map<String, SshForwardTunnel> CACHED_TUNNELS = Maps.newConcurrentMap();

    public SshForwardTunnel(final String host, final int port, final String username, final String password) throws JSchException {
        final JSch jsch = new JSch();
        final Session session = jsch.getSession(username, host, port);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("compression.s2c", "zlib,none");
        session.setConfig("compression.c2s", "zlib,none");
        session.setPassword(password);
        session.setDaemonThread(true);

        session.connect();
        this.session = session;
    }

    public SshForwardTunnel open(final int localPort, final String targetHost, final int targetPort) throws JSchException {
        int prev;
        do {
            prev = registeredCount.get();
            if (prev < 0) {
                throw new IllegalStateException("Reject");
            }
        } while (this.session.isConnected() && !registeredCount.compareAndSet(prev, prev + 1));

        boolean opened = false;
        try {
            session.setPortForwardingL(localPort, targetHost, targetPort);
            opened = true;
        } finally {
            if (opened) {
                registeredPorts.add(localPort);
            } else {
                registeredCount.decrementAndGet();
            }
        }
        return this;
    }

    public SshForwardTunnel close(final int localPort) throws JSchException {
        if (this.registeredPorts.remove(localPort)) {
            session.delPortForwardingL(localPort);
            registeredCount.decrementAndGet();
            System.out.println("Close local port: " + localPort);
            return this;
        } else {
            throw new IllegalStateException("local port " + localPort + " is not registered");
        }
    }

    public void shutdownGracefully() {
        while (!registeredCount.compareAndSet(0, -1)) {
            LockSupport.parkNanos( 100);
        }
        if (session.isConnected()) {
            session.disconnect();
        }
        System.out.println("shutdown");
    }

    public int open(final String targetHost, final int targetPort) throws SocketException {
        for (int i = 0; i < 10; i++) {
            try {
                final int localPort = acquireLocalPort();
                this.open(localPort, targetHost, targetPort);
                return localPort;
            } catch (final JSchException ex) {
                if (!Throwables.causedBy(ex, BindException.class)) {
                    throw new SocketException(ex.getMessage());
                }
            }
        }
        throw new SocketException();
    }

    private int acquireLocalPort() throws SocketException {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(0);
            return socket.getLocalPort();
        } finally {
            if (null != socket) {
                socket.close();
            }
        }
    }

}
