package com.zl.d17.controller;

import com.zl.d17.common.Result;
import com.zl.d17.config.AiProperties;
import com.zl.d17.conversation.HistoryMessageConverter;
import com.zl.d17.conversation.RoleConversationService;
import com.zl.d17.dto.ChatReplyResponse;
import com.zl.d17.dto.ChatRequest;
import com.zl.d17.dto.CitationDto;
import com.zl.d17.dto.RoleChatReplyResponse;
import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.config.HistoryProperties;
import com.zl.d17.knowledge.KnowledgeChatOrchestrator;
import com.zl.d17.service.AiChatResult;
import com.zl.d17.service.ChatInvocation;
import com.zl.d17.service.ThinkingSettings;
import com.zl.d17.service.ThinkingSettingsResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话控制器（D17 版）：同步聊天入口与健康检查。
 *
 * <p>D17 起所有链路（普通/角色）统一经过 {@link ChatHistoryService}：
 * 轮次开始先落 PENDING 用户消息，成功后用户与助手消息置 COMPLETED，
 * 失败则留 FAILED 与错误码；上下文窗口从 COMPLETED 历史中实时选取，
 * 会话间隔离、历史可跨重启查询。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class ChatController {

    private final KnowledgeChatOrchestrator orchestrator;

    private final RoleConversationService roleConversationService;

    private final ThinkingSettingsResolver thinkingSettingsResolver;

    private final AiProperties aiProperties;

    private final ChatHistoryService historyService;

    private final HistoryProperties historyProperties;

    public ChatController(KnowledgeChatOrchestrator orchestrator,
                          RoleConversationService roleConversationService,
                          ThinkingSettingsResolver thinkingSettingsResolver,
                          AiProperties aiProperties,
                          ChatHistoryService historyService,
                          HistoryProperties historyProperties) {
        this.orchestrator = orchestrator;
        this.roleConversationService = roleConversationService;
        this.thinkingSettingsResolver = thinkingSettingsResolver;
        this.aiProperties = aiProperties;
        this.historyService = historyService;
        this.historyProperties = historyProperties;
    }

    /**
     * 同步对话。角色链路返回 {@link RoleChatReplyResponse}，普通链路返回 {@link ChatReplyResponse}。
     */
    @PostMapping("/chat")
    public Result<Object> chat(@Valid @RequestBody ChatRequest request,
                               HttpServletRequest httpRequest) {
        String message = request.getMessage();
        int maxLength = aiProperties.getMaxMessageLength();
        if (message != null && message.length() > maxLength) {
            throw new IllegalArgumentException("message 长度超过当前上限 " + maxLength + " 个字符，请缩短后重试");
        }
        final ThinkingSettings settings = thinkingSettingsResolver.resolve(request);
        final boolean retrieveEnabled = Boolean.TRUE.equals(request.getRetrieveEnabled());
        final String owner = historyService.resolveOwner(
                httpRequest.getHeader(historyProperties.getDevUserHeader()));

        if (StringUtils.hasText(request.getScene())) {
            return Result.success(handleRoleChat(request, httpRequest, owner, settings, retrieveEnabled));
        }

        // 普通链路：D17 起同样落库并关联上下文窗口，不再使用空历史
        ChatHistoryService.TurnStart turn = historyService.startTurn(owner, request.getSessionId(),
                "", "", 0, request.getMessage());
        ChatInvocation invocation = new ChatInvocation(null, message,
                HistoryMessageConverter.toTurns(
                        historyService.getContextWindow(turn.session().getSessionId())));
        try {
            KnowledgeChatOrchestrator.SyncOutcome outcome =
                    orchestrator.chat(invocation, settings, retrieveEnabled);
            AiChatResult result = outcome.result();
            historyService.completeTurn(turn, result.content(), result.model());
            ChatReplyResponse reply = toReply(result, settings, outcome.citations());
            reply.setSessionId(turn.session().getSessionId());
            log.info("普通对话完成，sessionId={}，mode={}，mocked={}",
                    turn.session().getSessionId(), reply.getMode(), reply.getMocked());
            return Result.success(reply);
        } catch (Exception ex) {
            historyService.failTurn(turn, resolveErrorCode(ex), ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /**
     * 角色同步对话：模板校验与系统提示词渲染 → 带窗口的模型调用 → 终态落库。
     */
    private RoleChatReplyResponse handleRoleChat(ChatRequest request,
                                                 HttpServletRequest httpRequest,
                                                 String owner,
                                                 ThinkingSettings settings,
                                                 boolean retrieveEnabled) {
        RoleConversationService.PreparedConversation prepared =
                roleConversationService.prepare(request, owner);
        try {
            KnowledgeChatOrchestrator.SyncOutcome outcome =
                    orchestrator.chat(prepared.invocation(), settings, retrieveEnabled);
            AiChatResult result = outcome.result();
            // 仅最终答案落库，推理内容不持久化
            historyService.completeTurn(prepared.turn(), result.content(), result.model());
            log.info("角色对话完成，sessionId={}，templateId={}，version={}",
                    prepared.session().getSessionId(), prepared.session().getTemplateId(),
                    prepared.session().getTemplateVersion());
            return RoleChatReplyResponse.from(
                    prepared.session().getSessionId(),
                    prepared.session().getScene(),
                    prepared.session().getTemplateId(),
                    prepared.session().getTemplateVersion(),
                    toReply(result, settings, outcome.citations()),
                    settings.showReasoning() ? result.reasoningContent() : null,
                    outcome.citations());
        } catch (Exception ex) {
            historyService.failTurn(prepared.turn(), resolveErrorCode(ex), ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /**
     * 把结构化模型结果组装为对外 DTO；推理内容仅在显式受控展示时返回，否则强制为 null。
     */
    private ChatReplyResponse toReply(AiChatResult result, ThinkingSettings settings,
                                      List<CitationDto> citations) {
        String reasoning = settings.showReasoning() ? result.reasoningContent() : null;
        Boolean thinkingFlag = result.thinkingEnabled() ? Boolean.TRUE : null;
        return new ChatReplyResponse(result.mode(), result.model(), result.mocked(),
                result.content(), thinkingFlag, reasoning, citations);
    }

    /**
     * 健康检查：返回应用标识、运行模式与窗口配置。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("application", "D17_DHSA_MTDCA");
        info.put("mode", aiProperties.getMode());
        info.put("contextWindowMessages", historyProperties.getContextWindowMessages());
        info.put("devIdentity", "X-Dev-User 开发身份，非生产鉴权");
        return Result.success(info);
    }

    /**
     * 失败落库的错误码：映射到异常类名，便于后续按码统计。
     */
    private String resolveErrorCode(Exception ex) {
        String simpleName = ex.getClass().getSimpleName();
        return simpleName == null || simpleName.isBlank() ? "UNKNOWN" : simpleName;
    }
}
