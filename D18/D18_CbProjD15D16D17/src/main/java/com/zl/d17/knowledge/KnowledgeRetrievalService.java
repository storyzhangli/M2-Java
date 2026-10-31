package com.zl.d17.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 最小确定性知识检索服务。
 *
 * <p>不依赖 Embedding 与向量数据库：对用户问题做字符二元组（bigram）切分，
 * 在片段标题/章节/摘录上做可复现的关键词命中评分，按分数降序、chunkId 升序稳定排序，
 * 取前 {@code maxSources} 个正分片段。输出只可能是语料中真实存在的片段，
 * 无命中时返回空列表，由上层明确表达“无可用引用来源”，绝不伪造。</p>
 */
@Service
public class KnowledgeRetrievalService {

    private final KnowledgeCorpus corpus;

    private final KnowledgeProperties properties;

    public KnowledgeRetrievalService(KnowledgeCorpus corpus, KnowledgeProperties properties) {
        this.corpus = corpus;
        this.properties = properties;
    }

    /**
     * 按问题检索真实知识片段。
     *
     * @param query 用户原始问题
     * @return 去重后的有序命中片段；无命中返回空列表
     */
    public List<KnowledgeChunk> search(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Set<String> terms = buildTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : corpus.allChunks()) {
            int score = scoreChunk(chunk, terms);
            if (score > 0) {
                scored.add(new ScoredChunk(chunk, score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredChunk::score).reversed()
                .thenComparing(sc -> sc.chunk().chunkId()));

        return scored.stream()
                .limit(Math.max(1, properties.getMaxSources()))
                .map(ScoredChunk::chunk)
                .toList();
    }

    /**
     * 构造检索词：标点/空白切出的整词 + 中文连续串的字符二元组。
     * 全部小写归一，保证同一问题多次检索结果一致。
     */
    private Set<String> buildTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (isCjk(c)) {
                cjkRun.append(c);
            } else {
                flushCjk(cjkRun, terms);
                if (Character.isLetterOrDigit(c)) {
                    StringBuilder word = new StringBuilder();
                    while (i < normalized.length() && Character.isLetterOrDigit(normalized.charAt(i))
                            && !isCjk(normalized.charAt(i))) {
                        word.append(normalized.charAt(i));
                        i++;
                    }
                    i--;
                    if (word.length() >= 2) {
                        terms.add(word.toString());
                    }
                }
            }
        }
        flushCjk(cjkRun, terms);
        return terms;
    }

    private void flushCjk(StringBuilder run, Set<String> terms) {
        if (run.length() == 1) {
            terms.add(run.toString());
        }
        for (int i = 0; i < run.length() - 1; i++) {
            terms.add(run.substring(i, i + 2));
        }
        run.setLength(0);
    }

    private boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    /**
     * 标题/章节命中权重高于正文摘录；同一词在同一字段多次出现只计一次，避免长文本天然占优。
     */
    private int scoreChunk(KnowledgeChunk chunk, Set<String> terms) {
        int score = 0;
        score += countDistinctHits(nullSafe(chunk.title()).toLowerCase(Locale.ROOT), terms) * 3;
        score += countDistinctHits(nullSafe(chunk.section()).toLowerCase(Locale.ROOT), terms) * 2;
        score += countDistinctHits(nullSafe(chunk.excerpt()).toLowerCase(Locale.ROOT), terms);
        return score;
    }

    private int countDistinctHits(String field, Set<String> terms) {
        int hits = 0;
        for (String term : terms) {
            if (field.contains(term)) {
                hits++;
            }
        }
        return hits;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredChunk(KnowledgeChunk chunk, int score) {
    }
}
