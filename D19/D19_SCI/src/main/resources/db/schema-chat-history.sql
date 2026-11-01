-- ============================================================================
-- D17 对话历史持久化表 DDL（MySQL 8.x / 9.x，InnoDB，utf8mb4）
-- 可安全重复执行：CREATE TABLE IF NOT EXISTS，不会删除或覆盖任何已有数据。
-- 库：ai_db
-- 职责：chat_session 存会话元数据；chat_message 存完整消息（Chat History）。
-- 送入模型的有限上下文窗口（Chat Memory）由应用层从 chat_message 实时选取，不另建表。
-- 回滚见技术文档：DROP TABLE IF EXISTS chat_message; DROP TABLE IF EXISTS chat_session;
-- ============================================================================

CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `session_id`       VARCHAR(64)  NOT NULL COMMENT '业务会话标识，服务端生成，UUID 去横线',
    `owner_id`         VARCHAR(64)  NOT NULL DEFAULT 'dev-user' COMMENT '开发归属标识，非生产鉴权',
    `scene`            VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '角色场景，普通链路为空串',
    `title`            VARCHAR(200) NOT NULL DEFAULT '' COMMENT '会话标题，首条用户消息安全截断',
    `template_id`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '角色链路绑定模板标识',
    `template_version` INT          NOT NULL DEFAULT 0 COMMENT '绑定时刻模板版本',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE/CLOSED',
    `created_at`       DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`       DATETIME     NOT NULL COMMENT '更新时间',
    `last_message_at`  DATETIME     NOT NULL COMMENT '最近消息时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_owner_status` (`owner_id`, `status`, `last_message_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '对话会话表（D17 历史持久化）';

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `session_id`    VARCHAR(64)  NOT NULL COMMENT '所属会话业务标识',
    `owner_id`      VARCHAR(64)  NOT NULL COMMENT '开发归属标识，冗余便于隔离校验',
    `sequence_no`   INT          NOT NULL COMMENT '会话内单调递增序号，从1开始',
    `role`          VARCHAR(16)  NOT NULL COMMENT '消息角色：user/assistant',
    `content`       TEXT         NOT NULL COMMENT '消息正文',
    `status`        VARCHAR(16)  NOT NULL COMMENT '消息状态：PENDING/COMPLETED/FAILED/INTERRUPTED',
    `model_name`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '产出消息的模型名',
    `error_code`    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '失败/中断错误码',
    `error_message` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
    `created_at`    DATETIME     NOT NULL COMMENT '落库时间',
    `updated_at`    DATETIME     NOT NULL COMMENT '状态变更时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_sequence` (`session_id`, `sequence_no`),
    KEY `idx_session_status` (`session_id`, `status`, `sequence_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '对话消息表（D17 完整历史 Chat History）';
