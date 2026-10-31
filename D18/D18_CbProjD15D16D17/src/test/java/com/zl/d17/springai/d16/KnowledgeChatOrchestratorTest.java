package com.zl.d17.springai.d16;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.dto.ChatReplyResponse;
import com.zl.d17.knowledge.KnowledgeChatOrchestrator;
import com.zl.d17.knowledge.KnowledgeCorpus;
import com.zl.d17.knowledge.KnowledgeProperties;
import com.zl.d17.knowledge.KnowledgeRetrievalService;
import com.zl.d17.service.AiChatResult;
import com.zl.d17.service.AiChatService;
import com.zl.d17.service.ChatInvocation;
import com.zl.d17.service.ThinkingSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识问答编排器纯单元测试：用桩 AiChatService 模拟模型输出，
 * 重点证明模型自造的未知引用编号会被删除，且无检索时绝不伪造来源。
 */
class KnowledgeChatOrchestratorTest {

    private static KnowledgeRetrievalService retrievalService;

    /** 可控桩：同步调用返回预设答案，其余旧方法不应被本测试触达。 */
    private static final class StubAiChatService implements AiChatService {

        private final String cannedAnswer;

        private StubAiChatService(String cannedAnswer) {
            this.cannedAnswer = cannedAnswer;
        }

        @Override
        public ChatReplyResponse chat(String message) {
            throw new UnsupportedOperationException("本测试只走 D16 新方法");
        }

        @Override
        public ChatReplyResponse chat(ChatInvocation invocation) {
            throw new UnsupportedOperationException("本测试只走 D16 新方法");
        }

        @Override
        public Flux<String> streamChat(String message) {
            throw new UnsupportedOperationException("本测试只走 D16 新方法");
        }

        @Override
        public AiChatResult chat(ChatInvocation invocation, ThinkingSettings settings) {
            return new AiChatResult("deepseek", "deepseek-chat", false,
                    settings.enabled(), cannedAnswer, null);
        }
    }

    @BeforeAll
    static void initRetrieval() throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties();
        KnowledgeCorpus corpus = new KnowledgeCorpus(new ObjectMapper(), properties);
        corpus.load();
        retrievalService = new KnowledgeRetrievalService(corpus, properties);
    }

    private ChatInvocation invocation(String message) {
        return new ChatInvocation(null, message, List.of());
    }

    @Test
    void modelFabricatedUnknownMarkerMustBeStrippedWhileRealMarkerSurvives() {
        // 桩模拟模型输出：S9 是注册表中不存在的自造编号，S1 是真实分配编号
        StubAiChatService stub = new StubAiChatService("排查结论见 [S1]，但模型自造了 [S9] 来源。");
        KnowledgeChatOrchestrator orchestrator =
                new KnowledgeChatOrchestrator(stub, retrievalService, "deepseek");

        KnowledgeChatOrchestrator.SyncOutcome outcome = orchestrator.chat(
                invocation("数控机床主轴过热报警冷却液液位"), ThinkingSettings.legacyDefault(), true);

        assertThat(outcome.result().content())
                .contains("[S1]")
                .doesNotContain("[S9]")
                .doesNotContain("S9");
        assertThat(outcome.registry().getDroppedUnknownMarkers()).isEqualTo(1);
        assertThat(outcome.citations()).hasSize(1);
        assertThat(outcome.citations().get(0).id()).isEqualTo("S1");
        // 结构化来源必须能回溯到真实文档片段
        assertThat(outcome.citations().get(0).documentId()).isNotBlank();
        assertThat(outcome.citations().get(0).chunkId()).isNotBlank();
    }

    @Test
    void mockModeShouldAppendOnlyServerAssignedMarkers() {
        StubAiChatService stub = new StubAiChatService("离线确定性答案。");
        KnowledgeChatOrchestrator orchestrator =
                new KnowledgeChatOrchestrator(stub, retrievalService, "mock");

        KnowledgeChatOrchestrator.SyncOutcome outcome = orchestrator.chat(
                invocation("伺服电机编码器位置偏差报警"), ThinkingSettings.legacyDefault(), true);

        assertThat(outcome.result().content()).contains("参考来源：").contains("[S1]");
        assertThat(outcome.citations()).isNotEmpty();
        assertThat(outcome.registry().getDroppedUnknownMarkers()).isZero();
    }

    @Test
    void retrievalDisabledMustProduceNoCitationsAndStillSanitizeMarkers() {
        StubAiChatService stub = new StubAiChatService("普通答案夹带 [S3] 自造标记。");
        KnowledgeChatOrchestrator orchestrator =
                new KnowledgeChatOrchestrator(stub, retrievalService, "deepseek");

        KnowledgeChatOrchestrator.SyncOutcome outcome = orchestrator.chat(
                invocation("任意问题"), ThinkingSettings.legacyDefault(), false);

        assertThat(outcome.citations()).isEmpty();
        assertThat(outcome.result().content()).doesNotContain("[S3]");
        assertThat(outcome.registry().getDroppedUnknownMarkers()).isEqualTo(1);
    }

    @Test
    void noHitQueryMustNotFabricateSources() {
        StubAiChatService stub = new StubAiChatService("无资料时的普通回答。");
        KnowledgeChatOrchestrator orchestrator =
                new KnowledgeChatOrchestrator(stub, retrievalService, "deepseek");

        KnowledgeChatOrchestrator.SyncOutcome outcome = orchestrator.chat(
                invocation("今天天气如何适合钓鱼吗"), ThinkingSettings.legacyDefault(), true);

        assertThat(outcome.citations()).isEmpty();
        assertThat(outcome.result().content()).isEqualTo("无资料时的普通回答。");
    }
}
