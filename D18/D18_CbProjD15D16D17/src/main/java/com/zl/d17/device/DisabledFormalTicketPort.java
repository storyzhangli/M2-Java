package com.zl.d17.device;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 正式工单端口的默认禁用实现（D18）。
 *
 * <p>本阶段没有正式工单写入需求，提供该兜底实现让 Spring 容器在未自定义端口时可正常装配；
 * 一旦被误调用立即抛出 {@link UnsupportedOperationException}，不静默成功、不产生任何写入。
 * 后续迭代接入真实工单系统时，可提供自己的 {@link FormalTicketPort} Bean 覆盖本实现。</p>
 */
@Component
@ConditionalOnMissingBean(FormalTicketPort.class)
public class DisabledFormalTicketPort implements FormalTicketPort {

    @Override
    public Long createFormalTicket(FormalTicketCommand command) {
        throw new UnsupportedOperationException(
                "当前迭代未启用正式工单写入，候选结果不会自动建单/派单");
    }
}
