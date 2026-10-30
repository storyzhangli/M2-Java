package com.zl.d17.history.dto;

import java.time.LocalDateTime;

/**
 * 消息对外视图（不暴露数据库代理主键与冗余归属字段）。
 */
public record MessageView(
        int sequenceNo,
        String role,
        String content,
        String status,
        String modelName,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
