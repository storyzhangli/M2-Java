package com.zl.d17.conversation;

import com.zl.d17.dto.ChatRequest;
import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.model.ChatSession;
import com.zl.d17.history.model.HistoryMessage;
import com.zl.d17.prompt.PromptTemplateService;
import com.zl.d17.prompt.model.PromptTemplateEntity;
import com.zl.d17.service.ChatInvocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 角色对话服务（D17 版）：只保留“模板校验 + 系统提示词渲染”职责，
 * 会话绑定、消息持久化与上下文窗口全部委托 {@link ChatHistoryService}。
 *
 * <p>与 D15/D16 版本的差异：</p>
 * <ul>
 *     <li>不再调用 Redis 版 {@code ConversationMemory}，权威存储统一为 MySQL，
 *     历史可跨重启完整查询；Redis 组件保留为 Bean 但不进入主链路；</li>
 *     <li>prepare 一次完成模板校验与 PENDING 用户消息落库，成功/失败/中断的终态
 *     由控制器调用历史服务的 completeTurn/failTurn/interruptTurn 收口；</li>
 *     <li>不同角色会话隔离规则不变：同一 sessionId 绑定唯一角色，换角色必须新建会话。</li>
 * </ul>
 */
@Slf4j
@Service
public class RoleConversationService {

    private final PromptTemplateService promptTemplateService;

    private final ChatHistoryService historyService;

    public RoleConversationService(PromptTemplateService promptTemplateService,
                                   ChatHistoryService historyService) {
        this.promptTemplateService = promptTemplateService;
        this.historyService = historyService;
    }

    /**
     * 准备好的角色对话上下文：会话、模型调用参数与历史服务的轮次句柄。
     *
     * @param session    会话领域模型
     * @param invocation 已组装系统提示词与上下文窗口的模型调用参数
     * @param turn       历史服务轮次句柄，终态收口时使用
     */
    public record PreparedConversation(ChatSession session,
                                       ChatInvocation invocation,
                                       ChatHistoryService.TurnStart turn) {
    }

    /**
     * 校验模板并渲染系统提示词，同时完成会话绑定与 PENDING 用户消息落库。
     *
     * @param request 聊天请求（scene 非空）
     * @param owner   开发归属标识
     */
    public PreparedConversation prepare(ChatRequest request, String owner) {
        String scene = request.getScene();
        PromptTemplateEntity template = promptTemplateService.resolveEnabledRoleTemplate(scene);
        String systemPrompt = promptTemplateService.render(template, request.getVariables());

        ChatHistoryService.TurnStart turn = historyService.startTurn(owner, request.getSessionId(),
                scene, template.getTemplateId(), template.getVersion(), request.getMessage());

        List<HistoryMessage> history = historyService.getContextWindow(turn.session().getSessionId());
        ChatInvocation invocation = new ChatInvocation(
                systemPrompt, request.getMessage(), HistoryMessageConverter.toTurns(history));
        log.info("角色对话准备完成，sessionId={}，scene={}，模板版本={}，窗口消息数={}",
                turn.session().getSessionId(), scene, template.getVersion(), history.size());
        return new PreparedConversation(turn.session(), invocation, turn);
    }
}
