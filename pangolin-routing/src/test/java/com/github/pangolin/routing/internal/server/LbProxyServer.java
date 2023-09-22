package com.github.pangolin.routing.internal.server;

import com.github.pangolin.routing.internal.server.heathcheck.UrlTestHealthCheck;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LbProxyServer implements ProxyServer {
    private final String name;
    private final Map<String, ProxyServer> servers = new ConcurrentHashMap<>();
    private List<ProxyServer> aliveServers = new CopyOnWriteArrayList<>();
    private List<ProxyServer> deadServers = new CopyOnWriteArrayList<>();

    private UrlTestHealthCheck healthCheck = new UrlTestHealthCheck();
    private final EventLoopGroup g = new NioEventLoopGroup();

    public LbProxyServer(final String name, final List<ProxyServer> aliveServers) {
        this.name = name;
        for (ProxyServer aliveServer : aliveServers) {
            servers.put(aliveServer.name(), aliveServer);
        }
        this.aliveServers.addAll(aliveServers);
//        updateAlive();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler() {
        final ProxyServer p = aliveServers.get(new Random().nextInt(aliveServers.size()));
        return p.newProxyHandler();
    }

    /*-
     * 执行流程
     * 1. 从存活列表中选取节点
     * 2. 使用节点执行请求
     * 3. 如果执行成功返回
     * 4. 如果执行失败, 且是节点异常移动到僵尸列表, 启动僵尸检查, aliveCheckIntervalMillis
     * 5. 如果小于重试次数/时间, 重复
     * 6. 如果所有存活节点均已经尝试, 则从非前面加入的僵尸列表中尝试
     * 7. 如果僵尸成功执行, 移动僵尸到存活列表
     */

    /*-
     * 初始化节点检查:
     *
     */

    /*-
     * 僵尸检查:
     * 1. 执行节点请求
     * 2. 成功则清空失败次数, 从僵尸列表中删除, 加入存活列表
     * 3. 失败则累加失败次数
     */


    public void startHeathCheck() {
        g.scheduleWithFixedDelay(this::checkAliveHeath, 0, 5, TimeUnit.MINUTES);
    }

    public void checkAliveHeath() {
        for (Map.Entry<String, ProxyServer> entry : servers.entrySet()) {
            doCheckAliveHeath(entry.getValue());
        }
    }

    private void doCheckAliveHeath(final ProxyServer server) {
        healthCheck.heathCheck(server, g).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    // if zombie move to alive
                    if (deadServers.remove(server)) {
                        aliveServers.add(server);
                    }
                } else {
                    // if alive move to zombie
                    if (aliveServers.remove(server)) {
                        deadServers.add(server);
                    }
                }
            }
        });
    }
}