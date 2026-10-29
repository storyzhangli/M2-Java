package com.zl.d16.knowledge;

import com.zl.d16.dto.CitationDto;
import com.zl.d16.service.AiChatResult;
import com.zl.d16.service.AiChatService;
import com.zl.d16.service.ChatInvocation;
import com.zl.d16.service.StreamChunk;
import com.zl.d16.service.ThinkingSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 知识问答编排器（Orchestrator）：把“检索 → 提示词增强 → 模型调用 → 引用校验”串成一条受控链路。
 *
 * <p>关键可信约束：</p>
 * <ul>
 *     <li>检索关闭或无命中时不拼装来源、不伪造引用，模型答案按普通问答返回；</li>
 *     <li>引用编号由 {@link CitationRegistry} 按真实命中分配，模型自造编号一律被清洗删除；</li>
 *     <li>离线 mock 模式下，答案末尾的编号行由服务端基于真实注册表确定性生成，
 *     保证无外网环境也能完整演示闭环，且编号必然可回溯；</li>
 *     <li>每个请求的注册表都是方法内局部对象，天然请求级隔离。</li>
 * </ul>
 */
@Slf4j
@Component
public class KnowledgeChatOrchestrator {

    private final AiChatService aiChatService;

    private final KnowledgeRetrievalService retrievalService;

    private final boolean mockMode;

    public KnowledgeChatOrchestrator(
            AiChatService aiChatService,
            KnowledgeRetrievalService retrievalService,
            @Value("${app.ai.mode:mock}") String aiMode) {
        this.aiChatService = aiChatService;
        this.retrievalService = retrievalService;
        this.mockMode = !"deepseek".equalsIgnoreCase(aiMode);
    }

    /** 同步链路结果：结构化答案 + 本轮注册表（含实际引用与拦截计数）。 */
    public record SyncOutcome(AiChatResult result, CitationRegistry registry) {

        public List<CitationDto> citations() {
            return registry.citedCitations();
        }
    }

    /** 流式链路结果：类型化分片流 + 本轮注册表（complete 前读取实际引用）。 */
    public record StreamOutcome(Flux<StreamChunk> chunks, CitationRegistry registry) {
    }

    /**
     * 同步知识问答。
     */
    public SyncOutcome chat(ChatInvocation invocation, ThinkingSettings settings, boolean retrieveEnabled) {
        List<KnowledgeChunk> hits = retrieveEnabled
                ? retrievalService.search(invocation.userMessage())
                : List.of();
        CitationRegistry registry = new CitationRegistry(hits);

        if (!registry.hasSources()) {
            // 未启用检索或本轮无命中：走原始调用，空注册表同时兜底清洗任何自造 [Sn] 标记
            AiChatResult plain = aiChatService.chat(invocation, settings);
            String safeContent = registry.validateAndStrip(plain.content());
            return new SyncOutcome(replaceContent(plain, safeContent), registry);
        }

        ChatInvocation augmented = augment(invocation, registry.contextBlock());
        AiChatResult raw = aiChatService.chat(augmented, settings);
        String content = raw.content();
        if (mockMode) {
            // 离线演示：编号行由服务端生成，编号全部来自真实注册表
            content = nullSafe(content) + buildMockMarkerLine(registry);
        }
        String sanitized = registry.validateAndStrip(content);
        log.info("知识问答同步完成，分配来源数={}，实际引用数={}，拦截未知编号={}",
                registry.size(), registry.citedCitations().size(),
                registry.getDroppedUnknownMarkers());
        return new SyncOutcome(replaceContent(raw, sanitized), registry);
    }

    /**
     * 流式知识问答。内容分片逐块经过有状态标记清洗器，推理分片原样透传（默认不下发）；
     * mock 模式在答案尾部追加服务端生成的合法编号行。
     */
    public StreamOutcome stream(ChatInvocation invocation, ThinkingSettings settings, boolean retrieveEnabled) {
        List<KnowledgeChunk> hits = retrieveEnabled
                ? retrievalService.search(invocation.userMessage())
                : List.of();
        CitationRegistry registry = new CitationRegistry(hits);
        CitationRegistry.MarkerSanitizer sanitizer = registry.markerSanitizer();

        ChatInvocation effective = registry.hasSources()
                ? augment(invocation, registry.contextBlock())
                : invocation;

        Flux<StreamChunk> sanitized = aiChatService.streamChat(effective, settings)
                .map(chunk -> {
                    if (chunk instanceof StreamChunk.ContentChunk content) {
                        return new StreamChunk.ContentChunk(sanitizer.accept(content.text()));
                    }
                    return chunk;
                });

        Flux<StreamChunk> tail = Flux.defer(() -> {
            if (mockMode && registry.hasSources()) {
                String safe = sanitizer.accept(buildMockMarkerLine(registry));
                StreamChunk.ContentChunk last =
                        new StreamChunk.ContentChunk(safe + sanitizer.finish());
                return StringUtils.hasText(last.text())
                        ? Flux.<StreamChunk>just(last)
                        : Flux.empty();
            }
            String residual = sanitizer.finish();
            return StringUtils.hasText(residual)
                    ? Flux.just(new StreamChunk.ContentChunk(residual))
                    : Flux.empty();
        });

        return new StreamOutcome(Flux.concat(sanitized, tail)
                .doOnComplete(() -> log.info("知识问答流式完成，分配来源数={}，实际引用数={}，拦截未知编号={}",
                        registry.size(), registry.citedCitations().size(),
                        registry.getDroppedUnknownMarkers())), registry);
    }

    private ChatInvocation augment(ChatInvocation invocation, String contextBlock) {
        String systemPrompt = StringUtils.hasText(invocation.systemPrompt())
                ? invocation.systemPrompt() + "\n\n" + contextBlock
                : contextBlock;
        return new ChatInvocation(systemPrompt, invocation.userMessage(), invocation.history());
    }

    private String buildMockMarkerLine(CitationRegistry registry) {
        StringBuilder line = new StringBuilder(" 参考来源：");
        registry.allCitations().forEach(c -> line.append('[').append(c.id()).append(']'));
        line.append('。');
        return line.toString();
    }

    private AiChatResult replaceContent(AiChatResult raw, String safeContent) {
        return new AiChatResult(raw.mode(), raw.model(), raw.mocked(), raw.thinkingEnabled(),
                safeContent, raw.reasoningContent());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
