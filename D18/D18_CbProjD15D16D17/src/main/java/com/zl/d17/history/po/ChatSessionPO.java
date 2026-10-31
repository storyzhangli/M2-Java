package com.zl.d17.history.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话表 {@code chat_session} 的 MyBatis-Plus 持久化对象。
 *
 * <p>PO 只负责与数据库行映射，不直接暴露给 Controller；与领域模型
 * {@link com.zl.d17.history.model.ChatSession} 之间由服务层转换。</p>
 */
@Data
@NoArgsConstructor
@TableName("chat_session")
public class ChatSessionPO {

    /** 代理主键，数据库自增。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String ownerId;

    private String scene;

    private String title;

    private String templateId;

    private Integer templateVersion;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastMessageAt;
}
