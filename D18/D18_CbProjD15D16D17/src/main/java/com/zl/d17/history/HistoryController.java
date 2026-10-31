package com.zl.d17.history;

import com.zl.d17.common.Result;
import com.zl.d17.history.config.HistoryProperties;
import com.zl.d17.history.dto.MessageView;
import com.zl.d17.history.dto.PageView;
import com.zl.d17.history.dto.SessionView;
import com.zl.d17.history.model.ChatSession;
import com.zl.d17.history.model.HistoryMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话历史查询接口：会话列表与消息分页。
 *
 * <p>所有查询均按开发身份（请求头 {@code X-Dev-User}，缺省 dev-user）过滤；
 * 该身份是开发环境归属标识，不是生产鉴权。接口路径与现有聊天接口共用
 * {@code /api/v1/ai} 前缀，不新建第二套聊天入口。</p>
 */
@RestController
@RequestMapping("/api/v1/ai")
public class HistoryController {

    private final ChatHistoryService historyService;

    private final HistoryProperties properties;

    public HistoryController(ChatHistoryService historyService, HistoryProperties properties) {
        this.historyService = historyService;
        this.properties = properties;
    }

    /**
     * 分页查询当前归属的会话，按最近消息时间倒序。
     */
    @GetMapping("/sessions")
    public Result<PageView<SessionView>> listSessions(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String owner = resolveOwner(request);
        ChatHistoryService.PageResult<ChatSession> result = historyService.pageSessions(owner, page, size);
        PageView<SessionView> view = new PageView<>(
                result.records().stream().map(this::toSessionView).toList(),
                result.total(), result.page(), result.size());
        return Result.success(view);
    }

    /**
     * 分页查询指定会话的消息，按序号正序；先校验归属，无权访问统一返回参数错误。
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<PageView<MessageView>> listMessages(
            HttpServletRequest request,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String owner = resolveOwner(request);
        ChatHistoryService.PageResult<HistoryMessage> result =
                historyService.pageMessages(owner, sessionId, page, size);
        PageView<MessageView> view = new PageView<>(
                result.records().stream().map(this::toMessageView).toList(),
                result.total(), result.page(), result.size());
        return Result.success(view);
    }

    private String resolveOwner(HttpServletRequest request) {
        return historyService.resolveOwner(request.getHeader(properties.getDevUserHeader()));
    }

    private SessionView toSessionView(ChatSession session) {
        return new SessionView(
                session.getSessionId(),
                session.getScene(),
                session.getTitle(),
                session.getTemplateId(),
                session.getTemplateVersion(),
                session.getStatus().name(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getLastMessageAt());
    }

    private MessageView toMessageView(HistoryMessage message) {
        return new MessageView(
                message.getSequenceNo(),
                message.getRole(),
                message.getContent(),
                message.getStatus().name(),
                message.getModelName(),
                message.getErrorCode(),
                message.getErrorMessage(),
                message.getCreatedAt(),
                message.getUpdatedAt());
    }
}
