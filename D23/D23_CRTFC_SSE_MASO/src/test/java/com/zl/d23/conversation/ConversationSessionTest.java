package com.zl.d23.conversation;

import com.zl.d23.conversation.model.ConversationMessage;
import com.zl.d23.conversation.model.ConversationSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话内存模型回归测试（D23-R03，保留的学习/兼容资产）。
 *
 * <p>纯单元、不启动上下文：验证单会话 FIFO 截断、失败回滚与不可变快照。
 * 注意该模型不进主链路（权威存储为 MySQL ChatHistoryService），此处仅固化其行为。</p>
 */
class ConversationSessionTest {

    private static final int MAX_MESSAGES = 20;

    private ConversationMessage msg(String role, int index) {
        return new ConversationMessage(role, role + "-" + index, LocalDateTime.now());
    }

    private ConversationSession newSession() {
        return new ConversationSession("s1", "customer_service", "tpl-cs", 3, LocalDateTime.now());
    }

    @Test
    @DisplayName("R03 会话绑定字段在创建后保持不变")
    void session_bindingFieldsPreserved() {
        ConversationSession session = newSession();

        assertThat(session.getSessionId()).isEqualTo("s1");
        assertThat(session.getScene()).isEqualTo("customer_service");
        assertThat(session.getTemplateId()).isEqualTo("tpl-cs");
        assertThat(session.getTemplateVersion()).isEqualTo(3);
        assertThat(session.messageCount()).isZero();
    }

    @Test
    @DisplayName("R03 追加消息在容量内不截断且计数正确")
    void append_withinCapacity_keepsAll() {
        ConversationSession session = newSession();
        for (int i = 0; i < 5; i++) {
            session.appendMessage(msg(ConversationMessage.ROLE_USER, i),
                    MAX_MESSAGES, LocalDateTime.now());
        }

        assertThat(session.messageCount()).isEqualTo(5);
        assertThat(session.snapshotMessages()).hasSize(5);
    }

    @Test
    @DisplayName("R03 超过上限按 FIFO 丢弃最早消息、保留最新 N 条")
    void append_beyondCapacity_fifoTruncates() {
        ConversationSession session = newSession();
        for (int i = 0; i < 25; i++) {
            session.appendMessage(msg(ConversationMessage.ROLE_USER, i),
                    MAX_MESSAGES, LocalDateTime.now());
        }

        List<ConversationMessage> snapshot = session.snapshotMessages();
        assertThat(snapshot).hasSize(MAX_MESSAGES);
        // 最早 5 条被丢弃，保留 user-5 ... user-24
        assertThat(snapshot.get(0).content()).isEqualTo("user-5");
        assertThat(snapshot.get(MAX_MESSAGES - 1).content()).isEqualTo("user-24");
    }

    @Test
    @DisplayName("R03 移除最后一条消息：非空返回 true 并移除")
    void removeLast_nonEmpty_removesAndReturnsTrue() {
        ConversationSession session = newSession();
        session.appendMessage(msg(ConversationMessage.ROLE_USER, 1),
                MAX_MESSAGES, LocalDateTime.now());
        session.appendMessage(msg(ConversationMessage.ROLE_ASSISTANT, 2),
                MAX_MESSAGES, LocalDateTime.now());

        boolean removed = session.removeLastMessage(LocalDateTime.now());

        assertThat(removed).isTrue();
        assertThat(session.messageCount()).isEqualTo(1);
        assertThat(session.snapshotMessages().get(0).content()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("R03 空会话移除最后一条返回 false")
    void removeLast_empty_returnsFalse() {
        ConversationSession session = newSession();

        assertThat(session.removeLastMessage(LocalDateTime.now())).isFalse();
        assertThat(session.messageCount()).isZero();
    }

    @Test
    @DisplayName("R03 快照不可变，外部修改抛出异常")
    void snapshot_isImmutable() {
        ConversationSession session = newSession();
        session.appendMessage(msg(ConversationMessage.ROLE_USER, 1),
                MAX_MESSAGES, LocalDateTime.now());

        List<ConversationMessage> snapshot = session.snapshotMessages();

        assertThatThrownBy(() -> snapshot.add(msg(ConversationMessage.ROLE_USER, 9)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
