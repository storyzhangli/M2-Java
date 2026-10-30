package com.zl.d17.conversation.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多轮对话会话。
 *
 * <p>会话在首条消息时绑定角色场景与模板版本，之后不可更改：换角色必须新建会话，
 * 从结构上杜绝客服历史污染编程上下文。消息列表的所有读写都在对象监视器内完成，
 * 适配同一 sessionId 并发请求的边界场景。</p>
 */
public class ConversationSession {

    private final String sessionId;

    private final String scene;

    private final String templateId;

    /** 会话绑定时刻的模板版本，后续模板升级不影响进行中的会话口径，保证可追溯。 */
    private final int templateVersion;

    private final List<ConversationMessage> messages = new ArrayList<>();

    private final LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public ConversationSession(String sessionId, String scene, String templateId,
                               int templateVersion, LocalDateTime now) {
        this.sessionId = sessionId;
        this.scene = scene;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getScene() {
        return scene;
    }

    public String getTemplateId() {
        return templateId;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 追加一条消息；由 {@code ConversationMemory} 在持锁状态下调用。
     */
    public void appendMessage(ConversationMessage message, int maxMessages, LocalDateTime now) {
        messages.add(message);
        // FIFO 截断：超出上限丢弃最早消息，保证送模上下文长度有界
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
        this.updatedAt = now;
    }

    /**
     * 移除最后一条消息（模型调用失败时回滚刚加入的用户消息，避免失败轮次污染历史）。
     *
     * @return 是否实际移除
     */
    public boolean removeLastMessage(LocalDateTime now) {
        if (messages.isEmpty()) {
            return false;
        }
        messages.remove(messages.size() - 1);
        this.updatedAt = now;
        return true;
    }

    /**
     * 返回历史消息的不可变快照，供服务层组装模型消息。
     */
    public List<ConversationMessage> snapshotMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public int messageCount() {
        return messages.size();
    }
}
