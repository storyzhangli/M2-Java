package com.zl.d15.conversation;

import com.zl.d15.conversation.model.ConversationMessage;
import com.zl.d15.conversation.model.ConversationSession;
import com.zl.d15.prompt.model.PromptTemplateEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于内存的多轮会话存储。
 *
 * <p>与模板仓库一样属于无数据库条件下的最小实现：会话保存在 JVM 内存，重启丢失。
 * 容量使用访问有序的 {@link LinkedHashMap} 做上限保护，超出后淘汰最久未访问会话，
 * 避免长时间运行导致内存无界增长；所有读写在同一把监视器上串行化。</p>
 */
@Component
public class ConversationMemory {

    /** 最多保留的会话数，超出后按最久未访问淘汰。 */
    public static final int MAX_SESSIONS = 500;

    /** 每个会话送入模型的最大消息条数（用户与助手消息合计），FIFO 截断。 */
    public static final int MAX_MESSAGES_PER_SESSION = 20;

    private final Map<String, ConversationSession> sessions =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ConversationSession> eldest) {
                    return size() > MAX_SESSIONS;
                }
            };

    /**
     * 绑定或新建会话。
     *
     * <p>规则：sessionId 为空则新建；已存在会话必须与本次 scene 完全一致，
     * 否则拒绝（切换角色只能新会话），防止不相容历史被混用。</p>
     *
     * @return 绑定后的会话
     */
    public synchronized ConversationSession bind(String sessionId, PromptTemplateEntity template) {
        LocalDateTime now = LocalDateTime.now();
        if (sessionId == null || sessionId.isBlank()) {
            String newId = java.util.UUID.randomUUID().toString().replace("-", "");
            ConversationSession session = new ConversationSession(
                    newId, template.getScene(), template.getTemplateId(), template.getVersion(), now);
            sessions.put(newId, session);
            return session;
        }

        ConversationSession existing = sessions.get(sessionId);
        if (existing == null) {
            ConversationSession session = new ConversationSession(
                    sessionId, template.getScene(), template.getTemplateId(), template.getVersion(), now);
            sessions.put(sessionId, session);
            return session;
        }
        if (!existing.getScene().equals(template.getScene())) {
            throw new IllegalArgumentException(
                    "会话已绑定角色 " + existing.getScene() + "，不能在同一会话切换到 " + template.getScene()
                            + "，请新建会话后再切换角色");
        }
        return existing;
    }

    /**
     * 追加用户/助手消息，触发单会话 FIFO 截断。
     */
    public synchronized void appendMessage(String sessionId, ConversationMessage message) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在或已过期: " + sessionId);
        }
        session.appendMessage(message, MAX_MESSAGES_PER_SESSION, LocalDateTime.now());
    }

    /**
     * 模型调用失败时回滚最后一条（用户）消息。
     */
    public synchronized void rollbackLastMessage(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session != null) {
            session.removeLastMessage(LocalDateTime.now());
        }
    }

    /**
     * 主动清除会话（前端“新对话”可调用），不存在时幂等返回。
     */
    public synchronized void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    /** 当前会话总数，主要用于测试与运维观察。 */
    public synchronized int size() {
        return sessions.size();
    }
}
