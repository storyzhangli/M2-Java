package com.zl.d17.conversation;

import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.model.HistoryMessage;
import com.zl.d17.service.ChatInvocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史领域消息到 {@link ChatInvocation.Turn} 的转换器。
 *
 * <p>角色链路与普通链路共用同一转换规则：user/assistant 原样映射为 Turn，
 * 进入窗口的消息均为 COMPLETED 状态；Turn 是模型调用入参的内部结构，
 * 由具体 AiChatService 实现再转为 Spring AI Message。</p>
 */
public final class HistoryMessageConverter {

    private HistoryMessageConverter() {
    }

    /**
     * 批量转换，保持入参顺序（即会话序号正序）。
     */
    public static List<ChatInvocation.Turn> toTurns(List<HistoryMessage> history) {
        List<ChatInvocation.Turn> turns = new ArrayList<>(history.size());
        for (HistoryMessage item : history) {
            String role = ChatHistoryService.ROLE_ASSISTANT.equals(item.getRole())
                    ? ChatHistoryService.ROLE_ASSISTANT
                    : ChatHistoryService.ROLE_USER;
            turns.add(new ChatInvocation.Turn(role, item.getContent()));
        }
        return turns;
    }
}
