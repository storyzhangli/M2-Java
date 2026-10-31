package com.zl.d17.springai.history;

import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.model.HistoryMessage;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话与归属隔离测试（T04/T05）：不同会话窗口互不可见、不同归属无法读取他人会话。
 */
@SpringBootTest
class SessionIsolationTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private ChatHistoryService historyService;

    @Test
    void differentSessionsShouldNotShareContext() {
        ChatHistoryService.TurnStart a = historyService.startTurn(
                "ut_iso_a", null, "", "", 0, "A 的问题");
        historyService.completeTurn(a, "A 的回答", "offline-mock");

        ChatHistoryService.TurnStart b = historyService.startTurn(
                "ut_iso_b", null, "", "", 0, "B 的问题");
        historyService.completeTurn(b, "B 的回答", "offline-mock");

        List<HistoryMessage> windowA = historyService.getContextWindow(a.session().getSessionId());
        List<HistoryMessage> windowB = historyService.getContextWindow(b.session().getSessionId());
        assertThat(windowA).hasSize(2);
        assertThat(windowB).hasSize(2);
        assertThat(windowA.get(0).getContent()).isEqualTo("A 的问题");
        assertThat(windowB.get(0).getContent()).isEqualTo("B 的问题");
    }

    @Test
    void ownerCannotReadAnotherOwnersSession() {
        ChatHistoryService.TurnStart a = historyService.startTurn(
                "ut_iso_a", null, "", "", 0, "A 的私密问题");
        historyService.completeTurn(a, "A 的私密回答", "offline-mock");

        // 归属校验不通过：不返回任何会话内容，也不确认会话是否存在
        assertThatThrownBy(() -> historyService.requireOwnedSession(
                a.session().getSessionId(), "ut_iso_b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }
}
