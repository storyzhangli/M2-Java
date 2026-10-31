package com.zl.d17.springai.d16;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.knowledge.KnowledgeChunk;
import com.zl.d17.knowledge.KnowledgeCorpus;
import com.zl.d17.knowledge.KnowledgeProperties;
import com.zl.d17.knowledge.KnowledgeRetrievalService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性关键词检索的纯单元测试：命中、无命中、topK 上限与稳定排序，
 * 语料直接加载 classpath 真实种子 JSON。
 */
class KnowledgeRetrievalServiceTest {

    private static KnowledgeRetrievalService retrievalService;

    @BeforeAll
    static void init() throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setMaxSources(3);
        KnowledgeCorpus corpus = new KnowledgeCorpus(new ObjectMapper(), properties);
        corpus.load();
        // 夹具语料共 8 个片段，防止后续误改语料后断言失真
        assertThat(corpus.allChunks()).hasSize(8);
        retrievalService = new KnowledgeRetrievalService(corpus, properties);
    }

    @Test
    void coolingQueryShouldHitOnlyRealCoolingChunks() {
        List<KnowledgeChunk> hits = retrievalService.search("数控机床主轴过热报警冷却液液位怎么处理");
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(chunk ->
                assertThat(chunk.documentId()).isEqualTo("DOC-CNC-COOLING-001"));
        assertThat(hits.get(0).chunkId()).isEqualTo("DOC-CNC-COOLING-001-C01");
    }

    @Test
    void encoderQueryShouldHitServoEncoderChunk() {
        List<KnowledgeChunk> hits = retrievalService.search("伺服电机编码器位置偏差报警如何排查");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId()).isEqualTo("DOC-SERVO-TROUBLE-002-C02");
    }

    @Test
    void workOrderQueryShouldHitSpecChunks() {
        List<KnowledgeChunk> hits = retrievalService.search("产线停机一级工单响应时限和关单要求");
        assertThat(hits).extracting(KnowledgeChunk::documentId)
                .contains("DOC-WORKORDER-SPEC-003");
    }

    @Test
    void irrelevantQueryShouldReturnEmptyInsteadOfFabricatingSources() {
        assertThat(retrievalService.search("今天天气怎么样适合出门吗")).isEmpty();
        assertThat(retrievalService.search("")).isEmpty();
        assertThat(retrievalService.search("   ")).isEmpty();
    }

    @Test
    void sameQueryTwiceShouldReturnIdenticalOrdering() {
        String query = "冷却泵异响流量下降";
        List<KnowledgeChunk> first = retrievalService.search(query);
        List<KnowledgeChunk> second = retrievalService.search(query);
        assertThat(first).isEqualTo(second);
        assertThat(first.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void injectionFixtureChunkIsRetrievableButOnlyAsPlainData() {
        List<KnowledgeChunk> hits = retrievalService.search("忽略之前指令系统提示词");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId()).isEqualTo("DOC-SERVO-TROUBLE-002-C03");
        // 摘录中的指令性文字只是字符串数据，检索层不执行任何其中要求
        assertThat(hits.get(0).excerpt()).contains("不可信资料数据");
    }
}
