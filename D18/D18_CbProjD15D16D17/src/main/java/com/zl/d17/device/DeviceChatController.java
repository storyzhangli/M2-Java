package com.zl.d17.device;

import com.zl.d17.common.Result;
import com.zl.d17.device.dto.DeviceChatReply;
import com.zl.d17.device.dto.DeviceChatRequest;
import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.config.HistoryProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备场景对话控制器（D18）。
 *
 * <p>提供设备客服/技术工程师的同步对话入口，复用 {@link ChatHistoryService} 完成会话与多轮，
 * 响应中明确区分澄清职责与候选提取。本控制器不提供任何正式工单写入能力。</p>
 */
@RestController
@RequestMapping("/api/v1/device")
public class DeviceChatController {

    private final DeviceChatService deviceChatService;

    private final ChatHistoryService historyService;

    private final HistoryProperties historyProperties;

    public DeviceChatController(DeviceChatService deviceChatService,
                                ChatHistoryService historyService,
                                HistoryProperties historyProperties) {
        this.deviceChatService = deviceChatService;
        this.historyService = historyService;
        this.historyProperties = historyProperties;
    }

    /**
     * 设备场景同步对话。
     *
     * @param request     设备对话请求
     * @param httpRequest Servlet 请求，用于解析开发归属头
     */
    @PostMapping("/chat")
    public Result<DeviceChatReply> chat(@Valid @RequestBody DeviceChatRequest request,
                                        HttpServletRequest httpRequest) {
        String owner = historyService.resolveOwner(
                httpRequest.getHeader(historyProperties.getDevUserHeader()));
        return Result.success(deviceChatService.handle(request, owner));
    }
}
