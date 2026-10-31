package com.zl.d17.history.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息表 {@code chat_message} 的 MyBatis-Plus 持久化对象。
 */
@Data
@NoArgsConstructor
@TableName("chat_message")
public class HistoryMessagePO {

    /** 代理主键，数据库自增。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String ownerId;

    private Integer sequenceNo;

    private String role;

    private String content;

    private String status;

    private String modelName;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
