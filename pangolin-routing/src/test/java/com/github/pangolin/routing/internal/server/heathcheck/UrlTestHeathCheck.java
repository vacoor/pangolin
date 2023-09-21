package com.github.pangolin.routing.internal.server.heathcheck;

public class UrlTestHeathCheck {
    private final String url;

    public UrlTestHeathCheck(final String url) {
        this.url = url;
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
}