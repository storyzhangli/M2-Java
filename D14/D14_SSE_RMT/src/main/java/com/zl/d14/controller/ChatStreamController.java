package com.zl.d14.controller;

import com.zl.d14.common.AiConfigurationException;
import com.zl.d14.common.ResultCode;
import com.zl.d14.config.AiConstants;
import com.zl.d14.config.AiProperties;
import com.zl.d14.dto.ChatRequest;
import com.zl.d14.dto.SsePayloads;
import com.zl.d14.logging.TraceIdFilter;
import com.zl.d14.service.AiChatService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 流式对话控制器（SSE，Server-Sent Events）。
 *
 * <p>运行在 Servlet/Tomcat + Spring MVC 栈上，使用 MVC 原生 {@link SseEmitter} 作为异步响应载体，
 * 把 {@link AiChatService#streamChat(String)} 返回的 Reactor {@code Flux<String>} 增量桥接为
 * 命名 SSE 事件。该 Flux 仅作流式管道，不引入 WebFlux 服务端、不切换容器。</p>
 *
 * <p>事件协议（data 均为 UTF-8 JSON，由 Jackson 序列化，禁止手工拼帧）：</p>
 * <ul>
 *     <li>{@code start}：流开始，携带 requestId/mode/model；</li>
 *     <li>{@code delta}：文本增量 {@code {seq,content}}，seq 从 0 递增；</li>
 *     <li>{@code complete}：正常结束汇总，发出后不再有任何内容事件；</li>
 *     <li>{@code error}：流开始后的异常，携带安全 code/message，随后关闭连接。</li>
 * </ul>
 *
 * <p>异常分期处理：参数/长度校验在返回 emitter 之前抛出，由 {@code GlobalExceptionHandler}
 * 归一为普通 JSON 错误响应（此时 SSE 尚未开始）；流开始后的异常走 {@code error} 事件。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class ChatStreamController {

    private final AiChatService aiChatService;
    private final AiProperties aiProperties;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-flash}")
    private String deepseekModel;

    public ChatStreamController(AiChatService aiChatService, AiProperties aiProperties) {
        this.aiChatService = aiChatService;
        this.aiProperties = aiProperties;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        String message = request.getMessage();

        // 提交响应头之前的业务校验：直接抛异常，走既有全局异常处理器，返回统一 JSON（非 SSE）
        int maxLength = aiProperties.getMaxMessageLength();
        if (message != null && message.length() > maxLength) {
            throw new IllegalArgumentException("message 长度超过当前上限 " + maxLength + " 个字符，请缩短后重试");
        }

        // 关闭代理/浏览器缓冲，保证增量即时可达；字符编码随 produces 声明固定为 UTF-8
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(aiProperties.getStreamTimeoutMs());
        // 在请求线程抓取 requestId；reactor 回调线程不保证继承 MDC，后续日志显式带该值
        String requestId = MDC.get(TraceIdFilter.MDC_REQUEST_ID);
        String mode = aiProperties.getMode();
        String model = AiConstants.MODE_MOCK.equals(mode) ? "offline-mock" : deepseekModel;

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger seq = new AtomicInteger();
        AtomicInteger totalChars = new AtomicInteger();
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        Disposable subscription = aiChatService.streamChat(message)
                .doOnSubscribe(s -> {
                    // 订阅时刻仍在请求线程，先发送 start；若此刻连接已失效则直接终止，不继续消费模型流
                    boolean sent = safeSend(emitter, finished, "start",
                            new SsePayloads.Start(requestId, mode, model));
                    if (!sent) {
                        s.cancel();
                    }
                })
                .subscribe(
                        chunk -> onDelta(emitter, finished, subscriptionRef, seq, totalChars, chunk),
                        error -> onStreamError(emitter, finished, requestId, error),
                        () -> onComplete(emitter, finished, mode, model, seq, totalChars)
                );
        subscriptionRef.set(subscription);

        // 容器侧正常结束（含客户端读完关闭）：取消上游订阅，停止无意义的模型消费
        emitter.onCompletion(() -> {
            finished.set(true);
            subscription.dispose();
        });
        // 服务端超时：发安全错误事件（尽力而为）后关闭，并取消上游订阅释放资源
        emitter.onTimeout(() -> {
            log.warn("SSE 流超时关闭，requestId={}", requestId);
            finished.set(true);
            safeSend(emitter, finished, "error",
                    new SsePayloads.Error(ResultCode.SYSTEM_ERROR.getCode(), "流式响应超时，连接已关闭"));
            subscription.dispose();
            emitter.complete();
        });
        // 客户端断网/关闭页面等连接级错误：仅清理订阅，不向已失效连接写数据
        emitter.onError(ex -> {
            log.warn("SSE 连接异常/客户端断开，requestId={}，类型={}", requestId, ex.getClass().getSimpleName());
            finished.set(true);
            subscription.dispose();
        });

        log.info("SSE 流式对话已建立，requestId={}，mode={}，messageLength={}",
                requestId, mode, message == null ? 0 : message.length());
        return emitter;
    }

    private void onDelta(SseEmitter emitter,
                         AtomicBoolean finished,
                         AtomicReference<Disposable> subscriptionRef,
                         AtomicInteger seq,
                         AtomicInteger totalChars,
                         String chunk) {
        int currentSeq = seq.getAndIncrement();
        boolean sent = safeSend(emitter, finished, "delta", new SsePayloads.Delta(currentSeq, chunk));
        if (!sent) {
            // 连接已不可写：取消上游订阅，避免继续拉取/计费模型数据
            Disposable disposable = subscriptionRef.get();
            if (disposable != null) {
                disposable.dispose();
            }
            return;
        }
        totalChars.addAndGet(chunk.length());
    }

    private void onStreamError(SseEmitter emitter,
                               AtomicBoolean finished,
                               String requestId,
                               Throwable error) {
        ResultCode resultCode = resolveResultCode(error);
        // 诊断细节只记类型与 requestId，不记上游报文/密钥；对外给固定安全文案
        log.error("SSE 流异常结束，requestId={}，code={}，异常类型={}",
                requestId, resultCode.getCode(), error.getClass().getSimpleName());

        safeSend(emitter, finished, "error",
                new SsePayloads.Error(resultCode.getCode(), resultCode.getMsg()));
        finishQuietly(emitter, finished);
    }

    private void onComplete(SseEmitter emitter,
                            AtomicBoolean finished,
                            String mode,
                            String model,
                            AtomicInteger seq,
                            AtomicInteger totalChars) {
        boolean mocked = AiConstants.MODE_MOCK.equals(mode);
        boolean sent = safeSend(emitter, finished, "complete",
                new SsePayloads.Complete(seq.get(), mode, model, mocked, totalChars.get()));
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
