package com.zl.d17.springai.history;

import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.model.HistoryMessage;
import com.zl.d17.history.model.MessageStatus;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史失败留痕测试（T09/T10）：模型失败 FAILED 与客户端中断 INTERRUPTED 的状态与内容保留。
 */
@SpringBootTest
class HistoryFailureTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_fail";

    @Autowired
    private ChatHistoryService historyService;

    @Test
    void failedTurnShouldMarkUserFailedAndNotCreateAssistantMessage() {
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                OWNER, null, "", "", 0, "注定失败的问题");
        historyService.failTurn(turn, "MockFail", "模拟模型调用失败");

        Integer failedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM chat_message WHERE session_id = ? AND status = 'FAILED'",
                Integer.class, turn.session().getSessionId());
        Integer assistantRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM chat_message WHERE session_id = ? AND role = 'assistant'",
                Integer.class, turn.session().getSessionId());
        assertThat(failedRows).isEqualTo(1);
        assertThat(assistantRows).isZero();

        // 失败轮次不进入窗口
        assertThat(historyService.getContextWindow(turn.session().getSessionId())).isEmpty();
    }

    @Test
    void interruptedTurnShouldKeepPartialAssistantContent() {
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                OWNER, null, "", "", 0, "被中断的问题");
        historyService.interruptTurn(turn, "已经输出一半的内容",
                "offline-mock", "ClientAbort", "客户端中断");

        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM chat_message WHERE session_id = ? ORDER BY sequence_no",
                String.class, turn.session().getSessionId());
        assertThat(statuses).containsExactly("INTERRUPTED", "INTERRUPTED");

        // 中断轮次不进入窗口，避免半成品回答污染后续模型请求
        List<HistoryMessage> window = historyService.getContextWindow(turn.session().getSessionId());
        assertThat(window).noneMatch(m -> m.getStatus() == MessageStatus.INTERRUPTED);

        String partial = jdbcTemplate.queryForObject(
                "SELECT content FROM chat_message WHERE session_id = ? AND role = 'assistant'",
                String.class, turn.session().getSessionId());
        assertThat(partial).isEqualTo("已经输出一半的内容");
    }
}
