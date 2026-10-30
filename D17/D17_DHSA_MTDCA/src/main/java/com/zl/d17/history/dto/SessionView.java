package com.zl.d17.history.dto;

import java.time.LocalDateTime;

/**
 * 会话对外视图（不暴露数据库代理主键）。
 */
public record SessionView(
        String sessionId,
        String scene,
        String title,
        String templateId,
        int templateVersion,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastMessageAt) {
}
