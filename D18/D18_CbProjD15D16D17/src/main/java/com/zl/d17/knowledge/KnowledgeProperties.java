package com.zl.d17.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D16 最小知识库检索配置（前缀 {@code app.ai.knowledge}）。
 *
 * <p>当前实现为 classpath 语料 + 确定性关键词检索，不依赖向量数据库；
 * 配置项保留检索规模与语料位置的外部化能力，便于后续替换为真实 RAG 实现。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.knowledge")
public class KnowledgeProperties {

    /** 单次请求最多参与引用组装的片段数量，防止上下文无限膨胀。 */
    private int maxSources = 3;

    /** 种子语料的 classpath 位置。 */
    private String corpusLocation = "knowledge/equipment-fault-corpus.json";
}
