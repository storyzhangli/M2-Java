package com.zl.d16.conversation;

import com.zl.d16.conversation.model.ConversationMessage;
import com.zl.d16.conversation.model.ConversationSession;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话在 Redis 中的 JSON 载荷形态。
 *
 * <p>领域对象 {@link ConversationSession} 含 final 字段与
 * 不可变快照方法，不直接承担序列化契约；通过显式载荷 record 控制 Redis 中的 JSON 结构，
 * 避免依赖反射隐式构造器行为，序列化兼容性更可控。</p>
 *
 * @param sessionId       会话标识
 * @param scene           绑定的角色场景，创建后不可更改
 * @param templateId      绑定时刻的模板标识
 * @param templateVersion 绑定时刻的模板版本快照
 * @param messages        消息列表（已按 FIFO 上限截断）
 * @param createdAt       创建时间
 * @param updatedAt      最近活动时间
 */
public record RedisSessionPayload(
        String sessionId,
        String scene,
        String templateId,
        int templateVersion,
        List<ConversationMessage> messages,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
