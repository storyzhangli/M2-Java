package com.zl.d17.controller;

import com.zl.d17.common.Result;
import com.zl.d17.config.AiProperties;
import com.zl.d17.conversation.RoleConversationService;
import com.zl.d17.dto.ChatReplyResponse;
import com.zl.d17.dto.ChatRequest;
import com.zl.d17.dto.CitationDto;
import com.zl.d17.dto.RoleChatReplyResponse;
import com.zl.d17.knowledge.KnowledgeChatOrchestrator;
import com.zl.d17.service.AiChatResult;
import com.zl.d17.service.ChatInvocation;
import com.zl.d17.service.ThinkingSettings;
import com.zl.d17.service.ThinkingSettingsResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * AI 聊天入口控制器。
 *
 * <p>D16 在既有单轮/角色链路上增量接入两项能力：思考模式（Thinking Mode，推理内容与答案分离）
 * 与引用来源标注（Citation，只允许真实检索来源）。所有新参数均可缺省，缺省行为与旧版本一致。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class ChatController {

    private final KnowledgeChatOrchestrator orchestrator;

    private final AiProperties aiProperties;

    private final RoleConversationService roleConversationService;

    private final ThinkingSettingsResolver thinkingSettingsResolver;

    /**
     * 单轮同步聊天接口。
     *
     * <p>请求体不带 scene 时走普通链路；带 scene 时走角色对话：
     * 校验模板状态、渲染系统提示词、按会话隔离组装历史，并在响应中回传 sessionId 与模板版本。</p>
     *
     * @param request 请求体，{@code message} 必填且非空白
     * @return 统一包装的聊天响应（角色链路为 {@link RoleChatReplyResponse}）
     */
    @PostMapping("/chat")
    public Result<Object> chat(@Valid @RequestBody ChatRequest request) {
        String message = request.getMessage();

        // 业务可配置长度上限（注解 2000 为硬天花板，这里读取可外部调整的配置值再校验一次）
        int maxLength = aiProperties.getMaxMessageLength();
        if (message != null && message.length() > maxLength) {
            throw new IllegalArgumentException("message 长度超过当前上限 " + maxLength + " 个字符，请缩短后重试");
        }

        // 服务端白名单裁决思考设置；非法 effort/版本不支持等问题在此处统一抛 4001
        ThinkingSettings settings = thinkingSettingsResolver.resolve(request);
        boolean retrieveEnabled = Boolean.TRUE.equals(request.getRetrieveEnabled());

        // 日志仅记录长度、模式与开关，不打印消息全文、推理全文与任何密钥
        log.info("收到 AI 聊天请求，messageLength={}，scene={}，thinkingEnabled={}，retrieveEnabled={}",
                message == null ? 0 : message.length(), request.getScene(),
                settings.enabled(), retrieveEnabled);

        if (StringUtils.hasText(request.getScene())) {
            return Result.success(handleRoleChat(request, settings, retrieveEnabled));
        }

        ChatInvocation invocation = new ChatInvocation(null, message, List.of());
        KnowledgeChatOrchestrator.SyncOutcome outcome =
                orchestrator.chat(invocation, settings, retrieveEnabled);
        ChatReplyResponse response = toReply(outcome.result(), settings, outcome.citations());
        log.info("AI 聊天处理完成，mode={}，mocked={}，citations={}",
                response.getMode(), response.getMocked(), response.getCitations().size());
        return Result.success(response);
    }

    /**
     * 角色同步对话：准备会话与系统提示词 → 检索/模型调用 → 成功登记助手消息，失败回滚用户消息。
     */
    private RoleChatReplyResponse handleRoleChat(ChatRequest request,
                                                 ThinkingSettings settings,
                                                 boolean retrieveEnabled) {
        RoleConversationService.PreparedConversation prepared = roleConversationService.prepare(request);
        try {
            KnowledgeChatOrchestrator.SyncOutcome outcome = orchestrator.chat(
                    prepared.invocation(), settings, retrieveEnabled);
            AiChatResult result = outcome.result();
            // 会话记忆只允许写入最终答案，推理内容绝不入 Redis/MySQL 持久化链路
            roleConversationService.completeAssistant(
                    prepared.session().getSessionId(), result.content());
            log.info("角色对话完成，sessionId={}，templateId={}，version={}，拦截未知引用编号={}",
                    prepared.session().getSessionId(), prepared.session().getTemplateId(),
                    prepared.session().getTemplateVersion(), outcome.registry().getDroppedUnknownMarkers());
            return RoleChatReplyResponse.from(
                    prepared.session().getSessionId(),
                    prepared.session().getScene(),
                    prepared.session().getTemplateId(),
                    prepared.session().getTemplateVersion(),
                    toReply(result, settings, outcome.citations()),
                    settings.showReasoning() ? result.reasoningContent() : null,
                    outcome.citations());
        } catch (RuntimeException e) {
            roleConversationService.failAndRollback(prepared.session().getSessionId());
            throw e;
        }
    }

    /**
     * 把结构化模型结果组装为对外 DTO；推理内容仅在显式受控展示时返回，否则强制为 null。
     */
    private ChatReplyResponse toReply(AiChatResult result, ThinkingSettings settings,
                                      List<CitationDto> citations) {
        String reasoning = settings.showReasoning() ? result.reasoningContent() : null;
        // 关闭时返回 null 以便 NON_NULL 隐藏字段，旧客户端响应形态与 D16 之前完全一致
        Boolean thinkingFlag = result.thinkingEnabled() ? Boolean.TRUE : null;
        return new ChatReplyResponse(result.mode(), result.model(), result.mocked(),
                result.content(), thinkingFlag, reasoning, citations);
    }

    /**
     * 轻量健康检查/模式自检接口，便于启动后快速确认当前运行模式，不触发任何模型调用。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("application", "D16_CoT");
        info.put("mode", aiProperties.getMode());
        info.put("maxMessageLength", aiProperties.getMaxMessageLength());
        return Result.success(info);
    }
}
