package com.zl.d17.device;

/**
 * 正式工单写入端口（D18）。
 *
 * <p>这是正式写库/建单/派单的唯一入口边界。本迭代明确不启用正式工单写入：
 * 默认实现 {@link DisabledFormalTicketPort} 被调用即抛出异常，且设备编排与候选服务
 * <b>不注入该端口</b>，从依赖结构上保证候选提取无法触发正式写入。</p>
 */
public interface FormalTicketPort {

    /**
     * 创建正式工单（本阶段不会被调用）。
     *
     * @param command 正式工单命令
     * @return 正式工单主键
     */
    Long createFormalTicket(FormalTicketCommand command);
}
