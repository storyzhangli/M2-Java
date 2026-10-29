package com.zl.d15.springai.conversation;

import com.zl.d16.conversation.ConversationMemory;
import com.zl.d16.conversation.model.ConversationMessage;
import com.zl.d16.conversation.model.ConversationSession;
import com.zl.d16.prompt.PromptTemplateService;
import com.zl.d16.prompt.model.PromptTemplateEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话 Redis 存储的集成验证：绑定落 Key 与 TTL、FIFO 20 条截断、失败回滚、
 * 跨角色拒绝、缺失会话异常与清除幂等。
 */
@SpringBootTest
class RedisConversationMemoryTest {

    @Autowired
    private ConversationMemory memory;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private StringRedisTemplate redis;

    private String sessionId;

    @AfterEach
    void cleanup() {
        if (sessionId != null) {
            memory.clear(sessionId);
        }
    }

    @Test
    void bindShouldPersistSessionWithTtlAndEnforceSceneIsolation() {
        PromptTemplateEntity customer = promptTemplateService.resolveEnabledRoleTemplate("customer_service");
        ConversationSession session = memory.bind(null, customer);
        sessionId = session.getSessionId();

        String key = "d15f:conv:" + sessionId;
        assertThat(redis.hasKey(key)).isTrue();
        Long ttl = redis.getExpire(key);
        assertThat(ttl).isNotNull().isGreaterThan(0);

        // 同场景复用同一会话
        ConversationSession again = memory.bind(sessionId, customer);
        assertThat(again.getSessionId()).isEqualTo(sessionId);
        assertThat(again.getScene()).isEqualTo("customer_service");

        // 同 sessionId 切换角色必须拒绝
        PromptTemplateEntity programming = promptTemplateService.resolveEnabledRoleTemplate("programming");
        assertThatThrownBy(() -> memory.bind(sessionId, programming))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("不能在同一会话切换");
    }

    @Test
    void appendShouldKeepAtMostTwentyMessagesAndRollbackShouldRemoveLast() {
        PromptTemplateEntity programming = promptTemplateService.resolveEnabledRoleTemplate("programming");
        ConversationSession session = memory.bind(null, programming);
        sessionId = session.getSessionId();

        for (int i = 0; i < 21; i++) {
            memory.appendMessage(sessionId,
                    new ConversationMessage(ConversationMessage.ROLE_USER, "消息-" + i, LocalDateTime.now()));
        }
        ConversationSession loaded = memory.bind(sessionId, programming);
        assertThat(loaded.messageCount()).isEqualTo(ConversationMemory.MAX_MESSAGES_PER_SESSION);
        // FIFO 截断后最早一条被丢弃，保留的第一条是消息-1
        assertThat(loaded.snapshotMessages().get(0).content()).isEqualTo("消息-1");

        int beforeRollback = loaded.messageCount();
        memory.rollbackLastMessage(sessionId);
        ConversationSession afterRollback = memory.bind(sessionId, programming);
        assertThat(afterRollback.messageCount()).isEqualTo(beforeRollback - 1);

        memory.clear(sessionId);
        assertThat(redis.hasKey("d15f:conv:" + sessionId)).isFalse();
    }

    @Test
    void appendToMissingSessionShouldReject() {
        assertThatThrownBy(() -> memory.appendMessage("ut_session_not_exists",
                new ConversationMessage(ConversationMessage.ROLE_USER, "x", LocalDateTime.now())))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("会话不存在或已过期");

        // 回滚与清理对不存在的会话保持幂等，不抛异常
        memory.rollbackLastMessage("ut_session_not_exists");
        memory.clear("ut_session_not_exists");
    }
}
