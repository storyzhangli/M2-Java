package com.zl.d16.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 引用来源结构化数据（Citation，引用项）。
 *
 * <p>所有字段只能由后端根据本轮真实检索命中的知识片段组装，模型自身输出的文件路径、URL、页码等
 * 一律不得直接出现在本结构中；缺失的可选字段通过 {@link JsonInclude.Include#NON_NULL} 隐藏，
 * 不生成占位假数据，也不暴露本机绝对路径。</p>
 *
 * @param id         请求级引用编号，如 S1、S2，仅在本次请求/回答范围内有效
 * @param documentId 真实文档稳定标识
 * @param chunkId    真实文档片段稳定标识
 * @param title      文档标题或文件名
 * @param sourceType 来源类型（如 MANUAL/GUIDE/SPEC）
 * @param section    章节名，可能为 null
 * @param page       页码，可能为 null
 * @param excerpt    来自真实片段的有限摘录
 * @param url        数据源确实提供的有效链接，没有则为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CitationDto(String id,
                          String documentId,
                          String chunkId,
                          String title,
                          String sourceType,
                          String section,
                          Integer page,
                          String excerpt,
                          String url) {
}
