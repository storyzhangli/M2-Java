-- ============================================================================
-- D15_PLUS Prompt 模板持久化表 DDL（MySQL 8.x / 9.x，InnoDB，utf8mb4）
-- 可安全重复执行：CREATE TABLE IF NOT EXISTS，不会删除或覆盖任何已有数据。
-- 库：ai_db；字符集 utf8mb4 以兼容中文模板正文与 emoji。
-- 种子数据不在本脚本写入，由应用启动时 PromptSeedInitializer 幂等初始化。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `prompt_template` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `template_id` VARCHAR(64)  NOT NULL COMMENT '业务稳定标识，创建后不可改，如 customer_service',
    `name`        VARCHAR(100) NOT NULL COMMENT '模板展示名称',
    `scene`       VARCHAR(64)  NOT NULL COMMENT '场景/角色标识',
    `description` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '用途说明',
    `content`     TEXT         NOT NULL COMMENT '模板正文，ST4 单大括号占位符，字面括号需转义',
    `variables`   JSON         NOT NULL COMMENT '变量声明列表 JSON 数组',
    `version`     INT          NOT NULL DEFAULT 1 COMMENT '模板版本，每次更新自增',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '启停状态：ENABLED/DISABLED',
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    `created_at`  DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_id` (`template_id`),
    KEY `idx_scene_status_deleted` (`scene`, `status`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Prompt 模板表（D15_PLUS 持久化主存储）';
