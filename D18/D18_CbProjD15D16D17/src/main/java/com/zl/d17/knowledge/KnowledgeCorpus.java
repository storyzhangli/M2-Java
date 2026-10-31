package com.zl.d17.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备故障知识语料加载器。
 *
 * <p>启动时从 classpath JSON 读取教学夹具语料并扁平化为不可变 {@link KnowledgeChunk} 列表，
 * 作为 D16 引用来源功能唯一的真实数据来源。它不是向量库，也不伪装成企业知识库：
 * 文档中明确标注当前为“可替换的最小检索端口”，后续接入真实 RAG 时只需替换检索实现，
 * 请求级引用映射与校验契约保持不变。</p>
 */
@Slf4j
@Component
public class KnowledgeCorpus {

    private final ObjectMapper objectMapper;

    private final KnowledgeProperties properties;

    private volatile List<KnowledgeChunk> chunks = List.of();

    public KnowledgeCorpus(ObjectMapper objectMapper, KnowledgeProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** JSON 反序列化用的文档级结构。 */
    public record CorpusDocument(String documentId, String title, String sourceType, String url,
                                 List<CorpusChunk> chunks) {
    }

    /** JSON 反序列化用的片段级结构。 */
    public record CorpusChunk(String chunkId, String section, Integer page, String excerpt) {
    }

    /**
     * 加载并扁平化语料。public 可见性同时允许纯单元测试在不启动 Spring 容器的情况下手动触发。
     */
    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource(properties.getCorpusLocation()).getInputStream()) {
            CorpusDocument[] documents = objectMapper.readValue(in, CorpusDocument[].class);
            List<KnowledgeChunk> flattened = new ArrayList<>();
            for (CorpusDocument doc : documents) {
                if (doc.chunks() == null) {
                    continue;
                }
                for (CorpusChunk seed : doc.chunks()) {
                    flattened.add(new KnowledgeChunk(
                            doc.documentId(), seed.chunkId(), doc.title(), doc.sourceType(),
                            seed.section(), seed.page(), seed.excerpt(), doc.url()));
                }
            }
            this.chunks = List.copyOf(flattened);
            log.info("设备故障知识语料加载完成，文档数={}，片段数={}", documents.length, flattened.size());
        } catch (Exception e) {
            throw new IllegalStateException("加载知识语料失败: " + properties.getCorpusLocation(), e);
        }
    }

    /** 返回全部真实片段的不可变快照，检索评分在该快照上完成。 */
    public List<KnowledgeChunk> allChunks() {
        if (!StringUtils.hasText(properties.getCorpusLocation())) {
            throw new IllegalStateException("知识语料位置未配置");
        }
        return chunks;
    }
}
