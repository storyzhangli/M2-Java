package com.zl.d17.controller;

import com.zl.d17.common.AiConfigurationException;
import com.zl.d17.common.ResultCode;
import com.zl.d17.config.AiConstants;
import com.zl.d17.config.AiProperties;
import com.zl.d17.conversation.HistoryMessageConverter;
import com.zl.d17.conversation.RoleConversationService;
import com.zl.d17.dto.ChatRequest;
import com.zl.d17.dto.SsePayloads;
import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.config.HistoryProperties;
import com.zl.d17.knowledge.CitationRegistry;
import com.zl.d17.knowledge.KnowledgeChatOrchestrator;
import com.zl.d17.logging.TraceIdFilter;
import com.zl.d17.service.ChatInvocation;
import com.zl.d17.service.StreamChunk;
import com.zl.d17.service.ThinkingSettings;
import com.zl.d17.service.ThinkingSettingsResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 流式对话控制器（SSE，Server-Sent Events，服务器发送事件，D17 版）。
 *
 * <p>运行在 Servlet/Tomcat + Spring MVC 栈上，使用 MVC 原生 {@link SseEmitter} 作为异步响应载体，
 * 把知识编排器返回的 Reactor {@code Flux<StreamChunk>} 桥接为命名 SSE 事件。</p>
 *
 * <p>D17 持久化规则：流开始前 PENDING 用户消息落库；流过程中助手内容仅内存累积，
 * 不逐 chunk 写库；正常 complete 时助手全文 COMPLETED 落库；异常/超时走 FAILED；
 * 客户端断开且已有部分内容时保留 INTERRUPTED 助手消息。独立的 persisted 标志保证
 * 终态只落库一次，防止 Reactor 回调与容器回调重复写入。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class ChatStreamController {

    private static final long CONTAINER_TIMEOUT_GRACE_MS = 5000L;

    private final KnowledgeChatOrchestrator orchestrator;
    private final AiProperties aiProperties;
    private final RoleConversationService roleConversationService;
    private final ThinkingSettingsResolver thinkingSettingsResolver;
    private final ChatHistoryService historyService;
    private final HistoryProperties historyProperties;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-flash}")
    private String deepseekModel;

    @Value("${app.ai.thinking.reasoner-model:deepseek-reasoner}")
    private String reasonerModel;

    public ChatStreamController(KnowledgeChatOrchestrator orchestrator,
                                AiProperties aiProperties,
                                RoleConversationService roleConversationService,
                                ThinkingSettingsResolver thinkingSettingsResolver,
                                ChatHistoryService historyService,
                                HistoryProperties historyProperties) {
        this.orchestrator = orchestrator;
        this.aiProperties = aiProperties;
        this.roleConversationService = roleConversationService;
        this.thinkingSettingsResolver = thinkingSettingsResolver;
        this.historyService = historyService;
        this.historyProperties = historyProperties;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse response) {
        String message = request.getMessage();

        // SSE 开始前的校验直接抛异常，走全局异常处理器返回统一 JSON
        int maxLength = aiProperties.getMaxMessageLength();
        if (message != null && message.length() > maxLength) {
            throw new IllegalArgumentException("message 长度超过当前上限 " + maxLength + " 个字符，请缩短后重试");
        }
        final ThinkingSettings settings = thinkingSettingsResolver.resolve(request);
        final boolean retrieveEnabled = Boolean.TRUE.equals(request.getRetrieveEnabled());
        final String owner = historyService.resolveOwner(
                httpRequest.getHeader(historyProperties.getDevUserHeader()));

        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        // 轮次准备：角色链路走模板校验与渲染；普通链路直接开始轮次。两者都在此刻落 PENDING 用户消息。
        RoleConversationService.PreparedConversation roleContext = null;
        final ChatHistoryService.TurnStart turn;
        final ChatInvocation invocation;
        if (StringUtils.hasText(request.getScene())) {
            roleContext = roleConversationService.prepare(request, owner);
            turn = roleContext.turn();
            invocation = roleContext.invocation();
        } else {
            turn = historyService.startTurn(owner, request.getSessionId(),
                    "", "", 0, request.getMessage());
            invocation = new ChatInvocation(null, message,
                    HistoryMessageConverter.toTurns(
                            historyService.getContextWindow(turn.session().getSessionId())));
        }
        final RoleConversationService.PreparedConversation finalRoleContext = roleContext;
        final StringBuilder assistantBuffer = new StringBuilder();
        final AtomicBoolean persisted = new AtomicBoolean(false);

        long streamTimeoutMs = aiProperties.getStreamTimeoutMs();
        SseEmitter emitter = new SseEmitter(streamTimeoutMs + CONTAINER_TIMEOUT_GRACE_MS);
        String requestId = MDC.get(TraceIdFilter.MDC_REQUEST_ID);
        String mode = aiProperties.getMode();
        final String model = AiConstants.MODE_MOCK.equals(mode)
                ? "offline-mock"
                : (settings.enabled() ? reasonerModel : deepseekModel);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger deltaSeq = new AtomicInteger();
        AtomicInteger thinkingSeq = new AtomicInteger();
        AtomicInteger totalChars = new AtomicInteger();
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        KnowledgeChatOrchestrator.StreamOutcome outcome =
                orchestrator.stream(invocation, settings, retrieveEnabled);
        final CitationRegistry registry = outcome.registry();

        Disposable subscription = outcome.chunks()
                .timeout(Duration.ofMillis(streamTimeoutMs))
                .doOnSubscribe(s -> {
                    Boolean thinkingFlag = settings.enabled() ? Boolean.TRUE : null;
                    // D17：普通链路也已建立会话，start 事件回传 sessionId 供前端后续轮次沿用
                    SsePayloads.Start start = finalRoleContext == null
                            ? new SsePayloads.Start(requestId, mode, model,
                                    turn.session().getSessionId(), null, null, null, thinkingFlag)
                            : new SsePayloads.Start(requestId, mode, model,
                                    finalRoleContext.session().getSessionId(),
                                    finalRoleContext.session().getScene(),
                                    finalRoleContext.session().getTemplateId(),
                                    finalRoleContext.session().getTemplateVersion(),
                                    thinkingFlag);
                    boolean sent = safeSend(emitter, finished, "start", start);
                    if (!sent) {
                        s.cancel();
                    }
                })
                .subscribe(
                        chunk -> onChunk(emitter, finished, subscriptionRef, deltaSeq, thinkingSeq,
                                totalChars, assistantBuffer, settings, chunk),
                        error -> {
                            Disposable current = subscriptionRef.get();
                            if (current != null) {
                                current.dispose();
                            }
                            persistFailure(persisted, turn, error, model);
                            onStreamError(emitter, finished, requestId, error);
                        },
                        () -> {
                            if (!registry.citedCitations().isEmpty()) {
                                safeSend(emitter, finished, "citations",
                                        new SsePayloads.Citations(registry.citedCitations()));
                            }
                            if (persisted.compareAndSet(false, true)) {
                                // 仅最终答案落库，推理内容不持久化
                                historyService.completeTurn(turn, assistantBuffer.toString(), model);
                            }
                            onComplete(emitter, finished, mode, model, deltaSeq, totalChars, settings);
                        }
                );
        subscriptionRef.set(subscription);

        emitter.onCompletion(() -> {
            finished.set(true);
            subscription.dispose();
        });
        emitter.onTimeout(() -> {
            log.warn("SSE 容器兜底超时关闭，requestId={}", requestId);
            subscription.dispose();
            if (persisted.compareAndSet(false, true)) {
                historyService.failTurn(turn, "SseTimeout", "流式响应容器超时");
            }
            handleTimeout(emitter, finished);
        });
        emitter.onError(ex -> {
            log.warn("SSE 连接异常/客户端断开，requestId={}，类型={}", requestId, ex.getClass().getSimpleName());
            finished.set(true);
            subscription.dispose();
            // 客户端断开：有部分正文则保留 INTERRUPTED，无正文按失败收口
            if (persisted.compareAndSet(false, true)) {
                if (assistantBuffer.length() > 0) {
                    historyService.interruptTurn(turn, assistantBuffer.toString(), model,
                            "ClientDisconnect", "客户端断开连接，流式输出中断");
                } else {
                    historyService.failTurn(turn, "ClientDisconnect", "客户端断开连接，无助手内容");
                }
            }
        });

        log.info("SSE 流式对话已建立，requestId={}，mode={}，thinkingEnabled={}，retrieveEnabled={}，messageLength={}",
                requestId, mode, settings.enabled(), retrieveEnabled, message == null ? 0 : message.length());
        return emitter;
    }

    private void onChunk(SseEmitter emitter,
                         AtomicBoolean finished,
                         AtomicReference<Disposable> subscriptionRef,
                         AtomicInteger deltaSeq,
                         AtomicInteger thinkingSeq,
                         AtomicInteger totalChars,
                         StringBuilder assistantBuffer,
                         ThinkingSettings settings,
                         StreamChunk chunk) {
        if (chunk instanceof StreamChunk.ReasoningChunk reasoning) {
            if (!settings.showReasoning()) {
                return;
            }
            boolean sent = safeSend(emitter, finished, "thinking",
                    new SsePayloads.Thinking(thinkingSeq.getAndIncrement(), reasoning.text()));
            if (!sent) {
                cancelQuietly(subscriptionRef);
            }
            return;
        }

        StreamChunk.ContentChunk content = (StreamChunk.ContentChunk) chunk;
        int currentSeq = deltaSeq.getAndIncrement();
        boolean sent = safeSend(emitter, finished, "delta",
                new SsePayloads.Delta(currentSeq, content.text()));
        if (!sent) {
            cancelQuietly(subscriptionRef);
            return;
        }
        totalChars.addAndGet(content.text().length());
        assistantBuffer.append(content.text());
    }

    private void cancelQuietly(AtomicReference<Disposable> subscriptionRef) {
        Disposable disposable = subscriptionRef.get();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    /**
     * Reactor 错误回调中的失败落库：错误码用异常类名，错误信息只记类型，不记上游报文。
     */
    private void persistFailure(AtomicBoolean persisted, ChatHistoryService.TurnStart turn,
                                Throwable error, String model) {
        if (persisted.compareAndSet(false, true)) {
            String code = error.getClass().getSimpleName();
            if (error instanceof TimeoutException) {
                historyService.failTurn(turn, "StreamTimeout", "流式响应应用层超时");
            } else {
                historyService.failTurn(turn, code == null ? "UNKNOWN" : code, error.getClass().getSimpleName());
            }
        }
    }

    private void onStreamError(SseEmitter emitter,
                               AtomicBoolean finished,
                               String requestId,
                               Throwable error) {
        ResultCode resultCode = resolveResultCode(error);
        boolean timeout = error instanceof TimeoutException;
        String clientMessage = timeout ? "流式响应超时，连接已关闭" : resultCode.getMsg();
        if (timeout) {
            log.warn("SSE 流应用层超时结束，requestId={}，code={}，超时阈值={}ms",
                    requestId, resultCode.getCode(), aiProperties.getStreamTimeoutMs());
        } else {
            log.error("SSE 流异常结束，requestId={}，code={}，异常类型={}",
                    requestId, resultCode.getCode(), error.getClass().getSimpleName());
        }

        safeSend(emitter, finished, "error",
                new SsePayloads.Error(resultCode.getCode(), clientMessage));
        finishQuietly(emitter, finished);
    }

    private void onComplete(SseEmitter emitter,
                            AtomicBoolean finished,
                            String mode,
                            String model,
                            AtomicInteger seq,
                            AtomicInteger totalChars,
                            ThinkingSettings settings) {
        boolean mocked = AiConstants.MODE_MOCK.equals(mode);
        Boolean thinkingFlag = settings.enabled() ? Boolean.TRUE : null;
        boolean sent = safeSend(emitter, finished, "complete",
                new SsePayloads.Complete(seq.get(), mode, model, mocked, totalChars.get(),
                        thinkingFlag));
        if (sent) {
            finishQuietly(emitter, finished);
        }
    }

    private ResultCode resolveResultCode(Throwable error) {
        if (error instanceof AiConfigurationException || error instanceof NonTransientAiException) {
            return ResultCode.AI_CONFIG_ERROR;
        }
        if (error instanceof TransientAiException) {
            return ResultCode.AI_UNAVAILABLE;
        }
        return ResultCode.SYSTEM_ERROR;
    }

    private boolean safeSend(SseEmitter emitter, AtomicBoolean finished, String eventName, Object payload) {
        synchronized (emitter) {
            if (finished.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(eventName)
                        .data(payload, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                finished.set(true);
                return false;
            }
        }
    }

    private void handleTimeout(SseEmitter emitter, AtomicBoolean finished) {
        synchronized (emitter) {
            if (finished.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(new SsePayloads.Error(
                                        ResultCode.SYSTEM_ERROR.getCode(), "流式响应超时，连接已关闭"),
                                MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.warn("超时 error 事件发送失败，连接可能已关闭：{}", e.getClass().getSimpleName());
            }
            finished.set(true);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private void finishQuietly(SseEmitter emitter, AtomicBoolean finished) {
        synchronized (emitter) {
            if (finished.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }
}
