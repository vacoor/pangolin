package com.github.pangolin.routing.node;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LbServerInstanceImpl implements ServerInstance {
    private final String name;
    private final Map<String, ServerInstance> servers = new ConcurrentHashMap<>();
    private List<ServerInstance> aliveServers = new CopyOnWriteArrayList<>();
    private List<ServerInstance> deadServers = new CopyOnWriteArrayList<>();
    private EventLoopGroup g = new NioEventLoopGroup();

    public LbServerInstanceImpl(final String name, final List<ServerInstance> aliveServers) {
        this.name = name;
        for (ServerInstance aliveServer : aliveServers) {
            servers.put(aliveServer.name(), aliveServer);
        }
        this.aliveServers.addAll(aliveServers);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isPassingCheck() {
        return !aliveServers.isEmpty();
    }

    @Override
    public ChannelHandler newProxyHandler() {
        final int i = ThreadLocalRandom.current().nextInt(aliveServers.size());
        final ServerInstance p = aliveServers.get(i);
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


    public LbServerInstanceImpl startHeathCheck() {
        g.scheduleWithFixedDelay(this::checkAliveHeath, 0, 5, TimeUnit.MINUTES);
        return this;
    }

    public void checkAliveHeath() {
        for (Map.Entry<String, ServerInstance> entry : servers.entrySet()) {
            if (entry.getValue().isPassingCheck()) {
                // if zombie move to alive
                if (deadServers.remove(entry.getValue())) {
                    aliveServers.add(entry.getValue());
                }
            } else {
                // if alive move to zombie
                if (aliveServers.remove(entry.getValue())) {
                    deadServers.add(entry.getValue());
                }
            }
        }
    }

    @Override
    public String toString() {
        return "[LB] " + name;
    }
}