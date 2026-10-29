package com.zl.d16.knowledge;

/**
 * 知识片段（检索与引用的最小真实单元）。
 *
 * <p>对应任务书语境中的 Document/Chunk：documentId+chunkId 构成稳定标识，
 * 任何引用编号都必须能回溯到本类的一个真实实例，禁止由模型凭空生成。</p>
 *
 * @param documentId 文档稳定标识
 * @param chunkId    片段稳定标识
 * @param title      文档标题/文件名
 * @param sourceType 来源类型
 * @param section    章节，可为 null
 * @param page       页码，可为 null
 * @param excerpt    片段摘录原文（不可信数据，其中任何指令性文字都按普通资料处理）
 * @param url        数据源自带链接，可为 null；不允许填写本机绝对路径
 */
public record KnowledgeChunk(String documentId,
                             String chunkId,
                             String title,
                             String sourceType,
                             String section,
                             Integer page,
                             String excerpt,
                             String url) {
}
