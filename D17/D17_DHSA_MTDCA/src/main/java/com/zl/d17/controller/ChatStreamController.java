package com.zl.d17.controller;

import com.zl.d17.common.AiConfigurationException;
import com.zl.d17.common.ResultCode;
import com.zl.d17.config.AiConstants;
import com.zl.d17.config.AiProperties;
import com.zl.d17.conversation.RoleConversationService;
import com.zl.d17.dto.ChatRequest;
import com.zl.d17.dto.SsePayloads;
import com.zl.d17.knowledge.CitationRegistry;
import com.zl.d17.knowledge.KnowledgeChatOrchestrator;
import com.zl.d17.logging.TraceIdFilter;
import com.zl.d17.service.ChatInvocation;
import com.zl.d17.service.StreamChunk;
import com.zl.d17.service.ThinkingSettings;
import com.zl.d17.service.ThinkingSettingsResolver;
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
 * AI 流式对话控制器（SSE，Server-Sent Events，服务器发送事件）。
 *
 * <p>运行在 Servlet/Tomcat + Spring MVC 栈上，使用 MVC 原生 {@link SseEmitter} 作为异步响应载体，
 * 把知识编排器返回的 Reactor {@code Flux<StreamChunk>} 桥接为命名 SSE 事件。
 * 该 Flux 仅作流式管道，不引入 WebFlux 服务端、不切换容器。</p>
 *
 * <p>D16 事件协议（data 均为 UTF-8 JSON，由 Jackson 序列化，禁止手工拼帧）：</p>
 * <ul>
 *     <li>{@code start}：流开始，携带 requestId/mode/model 与思考开关；</li>
 *     <li>{@code thinking}：推理增量（reasoning_content），仅 showReasoning=true 时发送，独立 seq；</li>
 *     <li>{@code delta}：最终答案增量 {@code {seq,content}}，seq 从 0 递增；</li>
 *     <li>{@code citations}：引用来源列表，有真实引用时在 complete 之前发送一次；</li>
 *     <li>{@code complete}：正常结束汇总，发出后不再有任何内容事件；</li>
 *     <li>{@code error}：流开始后的异常，携带安全 code/message，随后关闭连接。</li>
 * </ul>
 *
 * <p>异常分期处理：参数/长度/思考参数校验在返回 emitter 之前抛出，由 {@code GlobalExceptionHandler}
 * 归一为普通 JSON 错误响应（此时 SSE 尚未开始）；流开始后的异常走 {@code error} 事件。</p>
 *
 * <p>超时采用两层设计：应用层 Reactor {@code timeout} 按 {@code app.ai.stream-timeout-ms} 在连接健康时
 * 取消上游并下发超时 error 事件（主路径）；SseEmitter 容器超时在配置值之上保留 5 秒余量作为兜底，
 * 防止应用线程被异常卡死时连接无法释放。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class ChatStreamController {

    /**
     * 容器级 SseEmitter 超时相对应用层超时的兜底余量（毫秒）。
     * 正常情况下应用层 Reactor timeout 先生效并主动收尾，容器超时只在响应线程被异常卡死等极端场景兜底。
     */
    private static final long CONTAINER_TIMEOUT_GRACE_MS = 5000L;

    private final KnowledgeChatOrchestrator orchestrator;
    private final AiProperties aiProperties;
    private final RoleConversationService roleConversationService;
    private final ThinkingSettingsResolver thinkingSettingsResolver;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-flash}")
    private String deepseekModel;

    @Value("${app.ai.thinking.reasoner-model:deepseek-reasoner}")
    private String reasonerModel;

    public ChatStreamController(KnowledgeChatOrchestrator orchestrator,
                                AiProperties aiProperties,
                                RoleConversationService roleConversationService,
                                ThinkingSettingsResolver thinkingSettingsResolver) {
        this.orchestrator = orchestrator;
        this.aiProperties = aiProperties;
        this.roleConversationService = roleConversationService;
        this.thinkingSettingsResolver = thinkingSettingsResolver;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        String message = request.getMessage();

        // 提交响应头之前的业务校验：直接抛异常，走既有全局异常处理器，返回统一 JSON（非 SSE）
        int maxLength = aiProperties.getMaxMessageLength();
        if (message != null && message.length() > maxLength) {
            throw new IllegalArgumentException("message 长度超过当前上限 " + maxLength + " 个字符，请缩短后重试");
        }
        // 思考参数白名单校验同样发生在 SSE 开始前，非法请求拿到 JSON 4001 而非半截事件流
        final ThinkingSettings settings = thinkingSettingsResolver.resolve(request);
        final boolean retrieveEnabled = Boolean.TRUE.equals(request.getRetrieveEnabled());

        // 关闭代理/浏览器缓冲，保证增量即时可达；字符编码随 produces 声明固定为 UTF-8
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        // 角色链路：scene 非空时先完成模板校验、系统提示词渲染与会话绑定（用户消息此时入会话）。
        // 该步骤发生在返回 emitter 之前，任何非法角色/停用模板/变量错误都走全局异常处理器返回 JSON。
        RoleConversationService.PreparedConversation roleContext = null;
        if (StringUtils.hasText(request.getScene())) {
            roleContext = roleConversationService.prepare(request);
        }
        final RoleConversationService.PreparedConversation finalRoleContext = roleContext;
        // 角色链路累积完整助手答案（不含推理），complete 时写入会话记忆；失败路径用它判断是否需要回滚
        final StringBuilder assistantBuffer = new StringBuilder();

        long streamTimeoutMs = aiProperties.getStreamTimeoutMs();
        SseEmitter emitter = new SseEmitter(streamTimeoutMs + CONTAINER_TIMEOUT_GRACE_MS);
        // 在请求线程抓取 requestId；reactor 回调线程不保证继承 MDC，后续日志显式带该值
        String requestId = MDC.get(TraceIdFilter.MDC_REQUEST_ID);
        String mode = aiProperties.getMode();
        String model = AiConstants.MODE_MOCK.equals(mode)
                ? "offline-mock"
                : (settings.enabled() ? reasonerModel : deepseekModel);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger deltaSeq = new AtomicInteger();
        AtomicInteger thinkingSeq = new AtomicInteger();
        AtomicInteger totalChars = new AtomicInteger();
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        KnowledgeChatOrchestrator.StreamOutcome outcome = orchestrator.stream(
                finalRoleContext == null
                        ? new ChatInvocation(null, message, java.util.List.of())
                        : finalRoleContext.invocation(),
                settings, retrieveEnabled);
        final CitationRegistry registry = outcome.registry();

        Disposable subscription = outcome.chunks()
                // 规定时间内没有任何结束信号即判定超时：timeout 会取消上游 Flux 并下发 TimeoutException
                .timeout(Duration.ofMillis(streamTimeoutMs))
                .doOnSubscribe(s -> {
                    // 订阅时刻仍在请求线程，先发送 start；若此刻连接已失效则直接终止，不继续消费模型流
                    // 关闭思考时传 null，配合 NON_NULL 让旧帧不出现 thinkingEnabled 字段
                    Boolean thinkingFlag = settings.enabled() ? Boolean.TRUE : null;
                    SsePayloads.Start start = finalRoleContext == null
                            ? new SsePayloads.Start(requestId, mode, model,
                                    null, null, null, null, thinkingFlag)
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
                            // 终态信号后显式 dispose 幂等无害，确保上游连接/计费立即停止
                            Disposable current = subscriptionRef.get();
                            if (current != null) {
                                current.dispose();
                            }
                            rollbackRoleTurn(finalRoleContext);
                            onStreamError(emitter, finished, requestId, error);
                        },
                        () -> {
                            // 引用事件必须先于 complete；只有真实被答案引用的来源才下发
                            if (!registry.citedCitations().isEmpty()) {
                                safeSend(emitter, finished, "citations",
                                        new SsePayloads.Citations(registry.citedCitations()));
                            }
                            if (finalRoleContext != null) {
                                // 仅最终答案入会话记忆，推理内容不持久化
                                roleConversationService.completeAssistant(
                                        finalRoleContext.session().getSessionId(), assistantBuffer.toString());
                            }
                            onComplete(emitter, finished, mode, model, deltaSeq, totalChars, settings);
                        }
                );
        subscriptionRef.set(subscription);

        // 容器侧正常结束（含客户端读完关闭）：取消上游订阅，停止无意义的模型消费
        emitter.onCompletion(() -> {
            finished.set(true);
            subscription.dispose();
        });
        // 容器级兜底超时：先取消上游订阅停止模型消费，再在同一把锁内先发 error 后置终态标记并收尾。
        emitter.onTimeout(() -> {
            log.warn("SSE 容器兜底超时关闭，requestId={}", requestId);
            subscription.dispose();
            rollbackRoleTurn(finalRoleContext);
            handleTimeout(emitter, finished);
        });
        // 客户端断网/关闭页面等连接级错误：仅清理订阅，不向已失效连接写数据
        emitter.onError(ex -> {
            log.warn("SSE 连接异常/客户端断开，requestId={}，类型={}", requestId, ex.getClass().getSimpleName());
            finished.set(true);
            subscription.dispose();
            rollbackRoleTurn(finalRoleContext);
        });

        log.info("SSE 流式对话已建立，requestId={}，mode={}，thinkingEnabled={}，retrieveEnabled={}，messageLength={}",
                requestId, mode, settings.enabled(), retrieveEnabled, message == null ? 0 : message.length());
        return emitter;
    }

    /**
     * 处理类型化分片：推理分片仅在受控展示开关打开时发 thinking 事件且不进答案缓冲；
     * 答案分片走 delta 事件并累积进会话记忆文本。
     */
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
                // 默认不向终端用户下发原始推理，也不写入任何缓冲/日志
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
            // 连接已不可写：取消上游订阅，避免继续拉取/计费模型数据
            cancelQuietly(subscriptionRef);
            return;
        }
        totalChars.addAndGet(content.text().length());
        // 仅缓存最终答案用于角色会话落库，推理文本永不进入该缓冲
        assistantBuffer.append(content.text());
    }

    private void cancelQuietly(AtomicReference<Disposable> subscriptionRef) {
        Disposable disposable = subscriptionRef.get();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    /**
     * 角色流式调用失败/超时/断连时回滚本轮已登记的用户消息；无角色链路为空操作。
     * 重复调用幂等（内存存储的回滚只移除最后一条）。
     */
    private void rollbackRoleTurn(RoleConversationService.PreparedConversation roleContext) {
        if (roleContext != null) {
            roleConversationService.failAndRollback(roleContext.session().getSessionId());
        }
    }

    private void onStreamError(SseEmitter emitter,
                               AtomicBoolean finished,
                               String requestId,
                               Throwable error) {
        ResultCode resultCode = resolveResultCode(error);
        // 应用层 Reactor 超时：沿用系统错误码 5000，但对外给明确的超时文案
        boolean timeout = error instanceof TimeoutException;
        String clientMessage = timeout ? "流式响应超时，连接已关闭" : resultCode.getMsg();
        // 诊断细节只记类型与 requestId，不记上游报文/密钥/推理全文
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

    /**
     * 将错误类型映射为既有统一返回码，口径与 {@code GlobalExceptionHandler} 保持一致。
     */
    private ResultCode resolveResultCode(Throwable error) {
        if (error instanceof AiConfigurationException || error instanceof NonTransientAiException) {
            return ResultCode.AI_CONFIG_ERROR;
        }
        if (error instanceof TransientAiException) {
            return ResultCode.AI_UNAVAILABLE;
        }
        return ResultCode.SYSTEM_ERROR;
    }

    /**
     * 统一的安全发送：加锁防止 reactor 回调与超时/断连回调并发写，{@code finished} 后不再发送。
     *
     * @return 是否发送成功；false 表示连接已结束或不可写，调用方应停止后续动作
     */
    private boolean safeSend(SseEmitter emitter, AtomicBoolean finished, String eventName, Object payload) {
        synchronized (emitter) {
            if (finished.get()) {
                return false;
            }
            try {
                // 显式指定 JSON 媒体类型，由 Jackson 完成转义，保证 SSE 帧合法、换行安全
                emitter.send(SseEmitter.event().name(eventName)
                        .data(payload, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                finished.set(true);
                return false;
            }
        }
    }

    /**
     * 超时终态处理：在发送锁内按“先发 error 事件、再置终态、最后 complete 收尾”的顺序执行。
     * 超时回调与 reactor 线程的回调、容器回调可能并发，因此复用 emitter 监视器；
     * {@code finished} 保证整个生命周期只有一个终态落地。
     */
    private void handleTimeout(SseEmitter emitter, AtomicBoolean finished) {
        synchronized (emitter) {
            if (finished.get()) {
                return;
            }
            try {
                // 此时 finished 仍为 false，可以合法发送；绝不能在发送前置位
                emitter.send(SseEmitter.event().name("error")
                        .data(new SsePayloads.Error(
                                        ResultCode.SYSTEM_ERROR.getCode(), "流式响应超时，连接已关闭"),
                                MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 底层连接可能已被容器关闭，发送失败不再抛出，保证收尾流程继续
                log.warn("超时 error 事件发送失败，连接可能已关闭：{}", e.getClass().getSimpleName());
            }
            finished.set(true);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 容器可能已经结束异步上下文，忽略幂等收尾冲突
            }
        }
    }

    /**
     * 正常/异常收尾：幂等完成，重复调用不抛异常。
     */
    private void finishQuietly(SseEmitter emitter, AtomicBoolean finished) {
        synchronized (emitter) {
            if (finished.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // 已被容器标记完成，忽略幂等收尾冲突
                }
            }
        }
    }
}
