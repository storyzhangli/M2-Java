package com.zl.d20;

import com.zl.d20.history.ChatHistoryService;
import com.zl.d20.history.model.HistoryMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话隔离测试（T07）。
 *
 * <p>验证不同开发归属、不同会话之间的消息互不串线，且无权访问他人会话。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class SessionIsolationTest {

    @Autowired
    private ChatHistoryService historyService;

    @Test
    @DisplayName("不同归属会话隔离且无权互访")
    void sessions_areIsolatedByOwner() {
        String ownerA = "ut_t07_a";
        String ownerB = "ut_t07_b";

        ChatHistoryService.TurnStart turnA = historyService.startTurn(
                ownerA, null, "", "", 0, "T07 用户 A 的问题");
        historyService.completeTurn(turnA, "T07 助手对 A 的回答", "offline-mock");

        ChatHistoryService.TurnStart turnB = historyService.startTurn(
                ownerB, null, "", "", 0, "T07 用户 B 的问题");
        historyService.completeTurn(turnB, "T07 助手对 B 的回答", "offline-mock");

        // B 无法访问 A 的会话，统一抛同一异常，不泄露会话是否存在
        assertThrows(IllegalArgumentException.class,
                () -> historyService.requireOwnedSession(ownerB, turnA.session().getSessionId()));

        // A 的上下文窗口只包含 A 自己的消息
        List<HistoryMessage> windowA = historyService.getContextWindow(turnA.session().getSessionId());
        assertThat(windowA).isNotEmpty();
        assertThat(windowA).allSatisfy(m -> {
            assertThat(m.getSessionId()).isEqualTo(turnA.session().getSessionId());
            assertThat(m.getOwnerId()).isEqualTo(ownerA);
        });
        assertThat(windowA.stream().map(HistoryMessage::getContent).toList())
                .doesNotContain("T07 助手对 B 的回答");
    }
}
