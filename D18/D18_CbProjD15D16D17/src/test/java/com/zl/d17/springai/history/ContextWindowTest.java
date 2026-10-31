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
 * 上下文窗口测试（T13）：窗口裁剪、轮次完整性补齐、非成功状态不进入窗口。
 * 用 properties 把窗口固定为 4 条，便于构造确定性断言。
 */
@SpringBootTest(properties = "app.ai.history.context-window-messages=4")
class ContextWindowTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_window";

    @Autowired
    private ChatHistoryService historyService;

    @Test
    void windowShouldContainOnlyLatestFourCompletedMessages() {
        ChatHistoryService.TurnStart t1 = historyService.startTurn(
                OWNER, null, "", "", 0, "问题一");
        historyService.completeTurn(t1, "回答一", "offline-mock");
        ChatHistoryService.TurnStart t2 = historyService.startTurn(
                OWNER, t1.session().getSessionId(), "", "", 0, "问题二");
        historyService.completeTurn(t2, "回答二", "offline-mock");
        ChatHistoryService.TurnStart t3 = historyService.startTurn(
                OWNER, t1.session().getSessionId(), "", "", 0, "问题三");
        historyService.completeTurn(t3, "回答三", "offline-mock");

        List<HistoryMessage> window = historyService.getContextWindow(t1.session().getSessionId());
        // 6 条历史只保留最近 4 条：问题二、回答二、问题三、回答三
        assertThat(window).hasSize(4);
        assertThat(window.get(0).getContent()).isEqualTo("问题二");
        assertThat(window.get(3).getContent()).isEqualTo("回答三");
    }

    @Test
    void failedAndPendingMessagesShouldNotEnterWindow() {
        ChatHistoryService.TurnStart t1 = historyService.startTurn(
                OWNER, null, "", "", 0, "成功轮次问题");
        historyService.completeTurn(t1, "成功轮次回答", "offline-mock");

        ChatHistoryService.TurnStart t2 = historyService.startTurn(
                OWNER, t1.session().getSessionId(), "", "", 0, "失败轮次问题");
        historyService.failTurn(t2, "MockFail", "模拟失败");

        List<HistoryMessage> window = historyService.getContextWindow(t1.session().getSessionId());
        assertThat(window).hasSize(2);
        assertThat(window).noneMatch(m -> "失败轮次问题".equals(m.getContent()));
    }

    @Test
    void ownerResolutionShouldRejectIllegalCharacters() {
        assertThatThrownBy(() -> historyService.resolveOwner("bad owner!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("开发身份标识非法");
        // 空白头回退默认归属
        assertThat(historyService.resolveOwner("   ")).isEqualTo("ut-default");
    }
}
