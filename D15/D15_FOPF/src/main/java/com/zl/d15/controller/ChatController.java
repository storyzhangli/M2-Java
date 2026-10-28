package com.zl.d15.controller;

import com.zl.d15.common.Result;
import com.zl.d15.config.AiProperties;
import com.zl.d15.dto.ChatReplyResponse;
import com.zl.d15.dto.ChatRequest;
import com.zl.d15.service.AiChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * 单轮同步聊天接口。
     *
     * @param request 请求体，{@code message} 必填且非空白
     * @return 统一包装的聊天响应
     */
    @PostMapping("/chat")
    public Result<ChatReplyResponse> chat(@Valid @RequestBody ChatRequest request) {
        String message = request.getMessage();

        // 业务可配置长度上限（注解 2000 为硬天花板，这里读取可外部调整的配置值再校验一次）
        int maxLength = aiProperties.getMaxMessageLength();
        if (message != null && message.length() > maxLength) {
            throw new IllegalArgumentException("message 长度超过当前上限 " + maxLength + " 个字符，请缩短后重试");
        }

        // 日志仅记录长度与模式，不打印消息全文、不打印任何密钥
        log.info("收到 AI 聊天请求，messageLength={}", message == null ? 0 : message.length());
        ChatReplyResponse response = aiChatService.chat(message);
        log.info("AI 聊天处理完成，mode={}，mocked={}", response.getMode(), response.getMocked());

        return Result.success(response);
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
