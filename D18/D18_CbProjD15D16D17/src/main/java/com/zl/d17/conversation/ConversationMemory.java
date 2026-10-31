package com.zl.d17.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.config.ConversationRedisProperties;
import com.zl.d17.conversation.model.ConversationMessage;
import com.zl.d17.conversation.model.ConversationSession;
import com.zl.d17.prompt.model.PromptTemplateEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的多轮会话存储（D15_PLUS 替换 D15 的 LinkedHashMap 内存实现）。
 *
 * <p>公开方法签名与内存版完全一致，{@link RoleConversationService} 与既有测试无需改动；
 * 每个会话以独立 String Key 保存 JSON 载荷，并在每次读写时刷新滑动 TTL：</p>
 * <ul>
 *     <li>会话天然属于短期状态，不写入 MySQL，TTL 到期由 Redis 自动回收，
 *     替代旧实现“500 会话 LRU”的进程内硬上限（容量治理方式变化已在技术文档说明）；</li>
 *     <li>单会话 20 条 FIFO 截断规则不变，在 {@link ConversationSession} 内完成；</li>
 *     <li>Redis 不可用时不做静默假成功，读写异常向上抛出由全局异常处理归一。</li>
 * </ul>
 */
@Slf4j
@Component
public class ConversationMemory {

    /** 每个会话送入模型的最大消息条数（用户与助手消息合计），FIFO 截断；与内存版口径保持一致。 */
    public static final int MAX_MESSAGES_PER_SESSION = 20;

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    private final ConversationRedisProperties properties;

    public ConversationMemory(StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              ConversationRedisProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 绑定或新建会话。规则与内存版一致：sessionId 为空则新建；已存在会话必须 scene 相同，
     * 否则拒绝切换角色；每次绑定刷新 TTL。
     */
    public ConversationSession bind(String sessionId, PromptTemplateEntity template) {
        LocalDateTime now = LocalDateTime.now();
        if (sessionId == null || sessionId.isBlank()) {
            String newId = UUID.randomUUID().toString().replace("-", "");
            ConversationSession session = new ConversationSession(
                    newId, template.getScene(), template.getTemplateId(), template.getVersion(), now);
            save(session);
            return session;
        }

        ConversationSession existing = load(sessionId, false);
        if (existing == null) {
            ConversationSession session = new ConversationSession(
                    sessionId, template.getScene(), template.getTemplateId(), template.getVersion(), now);
            save(session);
            return session;
        }
        if (!existing.getScene().equals(template.getScene())) {
            throw new IllegalArgumentException(
                    "会话已绑定角色 " + existing.getScene() + "，不能在同一会话切换到 " + template.getScene()
                            + "，请新建会话后再切换角色");
        }
        // 命中已有会话：仅刷新 TTL，不改变消息与更新时间
        redisTemplate.expire(key(sessionId), ttl());
        return existing;
    }

    /**
     * 追加用户/助手消息，触发单会话 FIFO 截断并刷新 TTL。
     */
    public void appendMessage(String sessionId, ConversationMessage message) {
        ConversationSession session = load(sessionId, true);
        session.appendMessage(message, MAX_MESSAGES_PER_SESSION, LocalDateTime.now());
        save(session);
    }

    /**
     * 模型调用失败时回滚最后一条（用户）消息；会话不存在时幂等返回。
     */
    public void rollbackLastMessage(String sessionId) {
        ConversationSession session = load(sessionId, false);
        if (session != null && session.removeLastMessage(LocalDateTime.now())) {
            save(session);
        }
    }

    /**
     * 主动清除会话（前端“新对话”），Key 不存在时 DEL 本身幂等。
     */
    public void clear(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    /**
     * 当前会话总数，仅用于测试与运维观察。
     *
     * <p>采用 SCANScan 游标而非 KEYS，避免阻塞 Redis；结果为近似值（扫描期间可能有过期/新增），
     * 不作为业务判断依据。</p>
     */
    public int size() {
        ScanOptions options = ScanOptions.scanOptions().match(properties.getKeyPrefix() + "*").count(200).build();
        Integer count = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Integer>) connection -> {
            int n = 0;
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    n++;
                }
            }
            return n;
        });
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 内部读写辅助
    // ------------------------------------------------------------------

    private void save(ConversationSession session) {
        try {
            RedisSessionPayload payload = new RedisSessionPayload(
                    session.getSessionId(),
                    session.getScene(),
                    session.getTemplateId(),
                    session.getTemplateVersion(),
                    new ArrayList<>(session.snapshotMessages()),
                    session.getCreatedAt(),
                    session.getUpdatedAt());
            redisTemplate.opsForValue().set(key(session.getSessionId()),
                    objectMapper.writeValueAsString(payload), ttl());
        } catch (Exception e) {
            throw new IllegalStateException("会话写入 Redis 失败，sessionId=" + session.getSessionId()
                    + "，原因: " + e.getMessage(), e);
        }
    }

    private ConversationSession load(String sessionId, boolean required) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(key(sessionId));
        } catch (Exception e) {
            throw new IllegalStateException("从 Redis 读取会话失败，sessionId=" + sessionId
                    + "，原因: " + e.getMessage(), e);
        }
        if (json == null) {
            if (required) {
                throw new IllegalArgumentException("会话不存在或已过期: " + sessionId);
            }
            return null;
        }
        try {
            RedisSessionPayload payload = objectMapper.readValue(json, RedisSessionPayload.class);
            ConversationSession session = new ConversationSession(
                    payload.sessionId(), payload.scene(), payload.templateId(),
                    payload.templateVersion(), payload.createdAt());
            List<ConversationMessage> messages = payload.messages();
            if (messages != null) {
                for (ConversationMessage message : messages) {
                    session.appendMessage(message, MAX_MESSAGES_PER_SESSION, payload.updatedAt());
                }
            }
            return session;
        } catch (Exception e) {
            throw new IllegalStateException("会话 JSON 反序列化失败，sessionId=" + sessionId
                    + "，原因: " + e.getMessage(), e);
        }
    }

    private Duration ttl() {
        return Duration.ofSeconds(properties.getTtlSeconds());
    }

    private String key(String sessionId) {
        return properties.getKeyPrefix() + sessionId;
    }
}
