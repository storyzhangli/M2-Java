package com.zl.d15.controller;

import com.zl.d15.common.Result;
import com.zl.d15.config.AiProperties;
import com.zl.d15.conversation.RoleConversationService;
import com.zl.d15.dto.ChatReplyResponse;
import com.zl.d15.dto.ChatRequest;
import com.zl.d15.dto.RoleChatReplyResponse;
import com.zl.d15.service.AiChatService;
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
import java.util.Map;

/**
 * AI 聊天入口控制器。
 *
 * <p>D01 仅暴露最小验证接口，不提前建设设备故障业务系统。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class ChatController {

    private final AiChatService aiChatService;

    private final AiProperties aiProperties;

    private final RoleConversationService roleConversationService;

    /**
     * 单轮同步聊天接口。
     *
     * <p>请求体不带 scene 时保持旧链路行为；带 scene 时走 D15 角色对话：
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

        // 日志仅记录长度与模式，不打印消息全文、不打印任何密钥
        log.info("收到 AI 聊天请求，messageLength={}，scene={}",
                message == null ? 0 : message.length(), request.getScene());

        if (StringUtils.hasText(request.getScene())) {
            return Result.success(handleRoleChat(request));
        }

        ChatReplyResponse response = aiChatService.chat(message);
        log.info("AI 聊天处理完成，mode={}，mocked={}", response.getMode(), response.getMocked());
        return Result.success(response);
    }

    /**
     * 角色同步对话：准备会话与系统提示词 → 调模型 → 成功登记助手消息，失败回滚用户消息。
     */
    private RoleChatReplyResponse handleRoleChat(ChatRequest request) {
        RoleConversationService.PreparedConversation prepared = roleConversationService.prepare(request);
        try {
            ChatReplyResponse reply = aiChatService.chat(prepared.invocation());
            roleConversationService.completeAssistant(prepared.session().getSessionId(), reply.getContent());
            log.info("角色对话完成，sessionId={}，templateId={}，version={}",
                    prepared.session().getSessionId(), prepared.session().getTemplateId(),
                    prepared.session().getTemplateVersion());
            return RoleChatReplyResponse.from(
                    prepared.session().getSessionId(),
                    prepared.session().getScene(),
                    prepared.session().getTemplateId(),
                    prepared.session().getTemplateVersion(),
                    reply);
        } catch (RuntimeException e) {
            roleConversationService.failAndRollback(prepared.session().getSessionId());
            throw e;
        }
    }

    /**
     * 轻量健康检查/模式自检接口，便于启动后快速确认当前运行模式，不触发任何模型调用。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("application", "D01_SpringAI_init");
        info.put("mode", aiProperties.getMode());
        info.put("maxMessageLength", aiProperties.getMaxMessageLength());
        return Result.success(info);
    }
}
