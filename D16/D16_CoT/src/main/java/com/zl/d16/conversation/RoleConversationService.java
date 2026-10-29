package com.zl.d16.conversation;

import com.zl.d16.conversation.model.ConversationMessage;
import com.zl.d16.conversation.model.ConversationSession;
import com.zl.d16.dto.ChatRequest;
import com.zl.d16.prompt.PromptTemplateService;
import com.zl.d16.prompt.model.PromptTemplateEntity;
import com.zl.d16.service.ChatInvocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色对话编排服务：负责模板选用校验、系统提示词渲染、会话绑定与历史组装。
 *
 * <p>它不直接调用模型，而是产出 {@link ChatInvocation} 交给 {@code AiChatService}，
 * 使“提示词管理/会话规则”与“模型适配（Mock/DeepSeek）”两个变化方向解耦。</p>
 */
@Slf4j
@Service
public class RoleConversationService {

    private final PromptTemplateService promptTemplateService;

    private final ConversationMemory conversationMemory;

    public RoleConversationService(PromptTemplateService promptTemplateService,
                                   ConversationMemory conversationMemory) {
        this.promptTemplateService = promptTemplateService;
        this.conversationMemory = conversationMemory;
    }

    /**
     * 一次角色调用的准备结果：绑定的会话与可直接送入模型服务的调用入参。
     */
    public record PreparedConversation(ConversationSession session, ChatInvocation invocation) {
    }

    /**
     * 校验角色/模板状态、渲染系统提示词、绑定会话并登记用户消息。
     *
     * @throws IllegalArgumentException 角色非法、模板停用、会话跨角色或变量校验失败时抛出（归一为 4001）
     */
    public PreparedConversation prepare(ChatRequest request) {
        String scene = request.getScene();
        if (!StringUtils.hasText(scene)) {
            throw new IllegalArgumentException("角色对话必须显式传入 scene，不允许仅靠前端标题区分角色");
        }

        // 服务端按 scene 取启用模板：停用/删除/非角色场景在此被拒绝
        PromptTemplateEntity template = promptTemplateService.resolveEnabledRoleTemplate(scene);
        String systemPrompt = promptTemplateService.render(template, request.getVariables());

        ConversationSession session = conversationMemory.bind(request.getSessionId(), template);

        List<ChatInvocation.Turn> history = session.snapshotMessages().stream()
                .map(m -> new ChatInvocation.Turn(m.role(), m.content()))
                .toList();

        // 用户消息先入会话；若后续模型调用失败，由 failAndRollback 撤回，避免失败轮次留在历史中
        conversationMemory.appendMessage(session.getSessionId(),
                new ConversationMessage(ConversationMessage.ROLE_USER, request.getMessage(), LocalDateTime.now()));

        ChatInvocation invocation =
                new ChatInvocation(systemPrompt, request.getMessage(), history);

        log.info("角色对话已就绪，sessionId={}，scene={}，templateId={}，version={}，历史条数={}",
                session.getSessionId(), scene, template.getTemplateId(), template.getVersion(), history.size());
        return new PreparedConversation(session, invocation);
    }

    /**
     * 模型成功返回后登记助手消息。
     */
    public void completeAssistant(String sessionId, String assistantContent) {
        conversationMemory.appendMessage(sessionId,
                new ConversationMessage(ConversationMessage.ROLE_ASSISTANT,
                        assistantContent, LocalDateTime.now()));
    }

    /**
     * 模型调用失败时回滚本轮用户消息，保持会话历史只包含成功轮次。
     */
    public void failAndRollback(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            conversationMemory.rollbackLastMessage(sessionId);
        }
    }
}
