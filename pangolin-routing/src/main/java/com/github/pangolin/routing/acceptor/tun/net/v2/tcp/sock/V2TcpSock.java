package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.sock;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpReceiveBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.connection.TcpSendBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.internal.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.pipeline.TcpSockChannel;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

/**
 * V2 TCP socket model: keep v1 {@link TcpSock} control-plane fields while preserving
 * v2 send/receive queue implementation ({@link TcpSendBuffer}/{@link TcpReceiveBuffer}).
 */
public class V2TcpSock extends TcpSock {

    private final TcpSendBuffer sendBuffer = new TcpSendBuffer();
    private final TcpReceiveBuffer receiveBuffer;

    private Channel netChannel;
    private EventLoop workerEventLoop;
    private FourTuple fourTuple;
    private TcpConnection tcpConnection;
    private TcpSockChannel sockChannel;

    public V2TcpSock(ByteBufAllocator allocator) {
        this.receiveBuffer = new TcpReceiveBuffer(allocator != null ? allocator : UnpooledByteBufAllocator.DEFAULT);
    }

    public TcpSendBuffer sendBuffer() {
        return tcpConnection != null ? tcpConnection.sendBuffer() : sendBuffer;
    }

    public TcpReceiveBuffer receiveBuffer() {
        return tcpConnection != null ? tcpConnection.receiveBuffer() : receiveBuffer;
    }

    public Channel netChannel() {
        return netChannel;
    }

    public void netChannel(Channel netChannel) {
        this.netChannel = netChannel;
    }

    public EventLoop workerEventLoop() {
        return workerEventLoop;
    }

    public void workerEventLoop(EventLoop workerEventLoop) {
        this.workerEventLoop = workerEventLoop;
    }

    public FourTuple fourTuple() {
        return fourTuple;
    }

    public void fourTuple(FourTuple fourTuple) {
        this.fourTuple = fourTuple;
    }

    public TcpConnection tcpConnection() {
        return tcpConnection;
    }

    public void tcpConnection(TcpConnection tcpConnection) {
        this.tcpConnection = tcpConnection;
    }

    public TcpSockChannel sockChannel() {
        return sockChannel;
    }

    public void sockChannel(TcpSockChannel sockChannel) {
        this.sockChannel = sockChannel;
    }
}
