package com.zl.d17.device;

import com.zl.d17.config.AiConstants;
import com.zl.d17.conversation.HistoryMessageConverter;
import com.zl.d17.device.dto.DeviceChatReply;
import com.zl.d17.device.dto.DeviceChatRequest;
import com.zl.d17.device.dto.FaultTicketCandidate;
import com.zl.d17.device.model.DeviceInformation;
import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.model.HistoryMessage;
import com.zl.d17.prompt.PromptTemplateService;
import com.zl.d17.prompt.model.PromptScenes;
import com.zl.d17.prompt.model.PromptTemplateEntity;
import com.zl.d17.service.AiChatResult;
import com.zl.d17.service.AiChatService;
import com.zl.d17.service.ChatInvocation;
import com.zl.d17.service.ThinkingSettings;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 设备场景应用编排服务（D18）。
 *
 * <p>把“版本化模板 → 会话/历史 → 设备信息档案 → 澄清 → 模型调用 → 候选提取 → 终态落库”
 * 串成一条最小链路：</p>
 * <ul>
 *   <li>会话与多轮全部复用 {@link ChatHistoryService}，不新增会话表/服务；</li>
 *   <li>需要澄清时，最终答复即澄清问题并附不确定性说明，不伪造引用；</li>
 *   <li>始终生成候选 DTO，但<b>不注入 {@link FormalTicketPort}</b>，结构上保证不会写正式工单。</li>
 * </ul>
 */
@Service
public class DeviceChatService {

    /** 本阶段允许的设备场景。 */
    private static final Set<String> DEVICE_SCENES =
            Set.of(PromptScenes.DEVICE_CUSTOMER_SERVICE, PromptScenes.TECHNICAL_ENGINEER);

    private final PromptTemplateService promptTemplateService;

    private final ChatHistoryService historyService;

    private final DeviceInformationExtractor informationExtractor;

    private final ClarificationService clarificationService;

    private final FaultTicketCandidateService candidateService;

    private final AiChatService aiChatService;

    public DeviceChatService(PromptTemplateService promptTemplateService,
                             ChatHistoryService historyService,
                             DeviceInformationExtractor informationExtractor,
                             ClarificationService clarificationService,
                             FaultTicketCandidateService candidateService,
                             AiChatService aiChatService) {
        this.promptTemplateService = promptTemplateService;
        this.historyService = historyService;
        this.informationExtractor = informationExtractor;
        this.clarificationService = clarificationService;
        this.candidateService = candidateService;
        this.aiChatService = aiChatService;
    }

    /**
     * 处理一次设备场景对话。
     *
     * @param request 设备对话请求
     * @param owner   开发归属标识
     */
    public DeviceChatReply handle(DeviceChatRequest request, String owner) {
        String scene = request.scene();
        if (!DEVICE_SCENES.contains(scene)) {
            throw new IllegalArgumentException(
                    "设备场景非法，仅支持 device_customer_service / technical_engineer");
        }

        PromptTemplateEntity template = promptTemplateService.resolveEnabledRoleTemplate(scene);
        String systemPrompt = promptTemplateService.render(template, request.variables());

        ChatHistoryService.TurnStart turn = historyService.startTurn(owner, request.sessionId(),
                scene, template.getTemplateId(), template.getVersion(), request.message());
        try {
            List<HistoryMessage> prior =
                    historyService.getContextWindow(turn.session().getSessionId());

            DeviceInformation info = informationExtractor.build(request.message(), prior);
            ClarificationService.ClarificationResult clarification = clarificationService.evaluate(info);

            ChatInvocation invocation = new ChatInvocation(systemPrompt, request.message(),
                    HistoryMessageConverter.toTurns(prior));
            AiChatResult aiResult = aiChatService.chat(invocation, ThinkingSettings.legacyDefault());

            FaultTicketCandidate candidate = candidateService.build(info, clarification);
            String answer = buildAnswer(aiResult, clarification);

            historyService.completeTurn(turn, answer, aiResult.model());
            return new DeviceChatReply(
                    turn.session().getSessionId(),
                    scene,
                    template.getTemplateId(),
                    template.getVersion(),
                    aiResult.mode(),
                    aiResult.model(),
                    aiResult.mocked(),
                    answer,
                    clarification.clarificationRequired(),
                    clarification.missingInformation(),
                    clarification.clarificationQuestion(),
                    clarification.uncertaintyNote(),
                    candidate
            );
        } catch (Exception ex) {
            historyService.failTurn(turn, ex.getClass().getSimpleName(), ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /**
     * 构建最终答复：需要澄清时以澄清问题与不确定性说明为答复，否则采用模型确定性回复。
     */
    private String buildAnswer(AiChatResult aiResult,
                               ClarificationService.ClarificationResult clarification) {
        if (clarification.clarificationRequired()) {
            return AiConstants.MOCK_REPLY_MARKER + " 设备信息尚不完整，需要先澄清。"
                    + clarification.clarificationQuestion()
                    + System.lineSeparator() + clarification.uncertaintyNote();
        }
        return aiResult.content();
    }
}
