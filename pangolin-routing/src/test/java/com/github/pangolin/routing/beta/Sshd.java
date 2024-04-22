package com.github.pangolin.routing.beta;

import io.netty.channel.ChannelDuplexHandler;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.DefaultConfigFileHostEntryResolver;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.kex.DHGClient;
import org.apache.sshd.client.kex.DHGEXClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSessionImpl;
import org.apache.sshd.client.session.SessionFactory;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.cipher.CipherFactory;
import org.apache.sshd.common.io.IoConnectFuture;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.netty.NettyIoConnector;
import org.apache.sshd.netty.NettyIoServiceFactory;
import org.apache.sshd.netty.NettyIoServiceFactoryFactory;
import org.apache.sshd.netty.NettyIoSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;

public class Sshd {


    public static void main(String[] args) throws Exception {
        SshClient client = ClientBuilder.builder()
                .build();

        client.setIoServiceFactoryFactory(new NettyIoServiceFactoryFactory() { });

        client.start();
        /*
        ConnectFuture cf = client.connect("vacoor", "127.0.0.1", 22);
        cf.await();
//
//
        System.out.println("OK");
        ClientSession clientSession = cf.getClientSession();
        */

        final NettyIoServiceFactoryFactory ff = new NettyIoServiceFactoryFactory();
        final SessionFactory sessionFactory = new SessionFactory(client);

        NettyIoConnector c = new NettyIoConnector((NettyIoServiceFactory) ff.create(client), sessionFactory) {
            @Override
            public IoConnectFuture connect(final SocketAddress address, final AttributeRepository context, final SocketAddress localAddress) {
                return super.connect(address, context, localAddress);
            }
        };

        InetSocketAddress sa = new InetSocketAddress("127.0.0.1", 22);
        IoConnectFuture cf = c.connect(sa, null, null);
        boolean await = cf.await();

        // NettyIoSession session = new NettyIoSession(c, sessionFactory, sa);
        NettyIoSession session = (NettyIoSession) cf.getSession();
        ClientSession clientSession = new ClientSessionImpl(client, session);


        clientSession.addPasswordIdentity("xltaifsgen");
        AuthFuture auth = clientSession.auth();
        auth.await();
        System.out.println(auth.isSuccess());
        /*
        */
    }
}