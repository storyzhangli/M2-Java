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
 * 对话历史持久化测试（T01/T03/T06）：
 * 落库状态、多轮序号连续、持久化独立于进程内状态（模拟重启后仍可查询）。
 */
@SpringBootTest
class HistoryPersistenceTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_persist";

    @Autowired
    private ChatHistoryService historyService;

    @Test
    void startTurnShouldPersistPendingUserAndCompleteTurnShouldKeepBothCompleted() {
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                OWNER, null, "", "", 0, "第一条用户问题");

        // 独立 SQL 通道验证 PENDING 行真实落库，不依赖服务内存状态
        Integer pendingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM chat_message WHERE session_id = ? AND status = 'PENDING'",
                Integer.class, turn.session().getSessionId());
        assertThat(pendingRows).isEqualTo(1);

        historyService.completeTurn(turn, "第一条助手回复", "offline-mock");

        List<HistoryMessage> window = historyService.getContextWindow(turn.session().getSessionId());
        assertThat(window).hasSize(2);
        assertThat(window.get(0).getRole()).isEqualTo("user");
        assertThat(window.get(0).getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(window.get(1).getRole()).isEqualTo("assistant");
        assertThat(window.get(1).getContent()).isEqualTo("第一条助手回复");
    }

    @Test
    void multipleTurnsShouldGetGaplessSequenceAndOrderedMessages() {
        ChatHistoryService.TurnStart t1 = historyService.startTurn(
                OWNER, null, "", "", 0, "问题一");
        historyService.completeTurn(t1, "回答一", "offline-mock");

        ChatHistoryService.TurnStart t2 = historyService.startTurn(
                OWNER, t1.session().getSessionId(), "", "", 0, "问题二");
        historyService.completeTurn(t2, "回答二", "offline-mock");

        ChatHistoryService.TurnStart t3 = historyService.startTurn(
                OWNER, t1.session().getSessionId(), "", "", 0, "问题三");
        historyService.completeTurn(t3, "回答三", "offline-mock");

        List<HistoryMessage> all = historyService.getContextWindow(t1.session().getSessionId());
        assertThat(all).hasSize(6);
        for (int i = 0; i < all.size(); i++) {
            assertThat(all.get(i).getSequenceNo()).isEqualTo(i + 1);
        }
        // 序号 1..6 按序排列：index 1 为第一轮助手回答，index 2 为第二轮用户提问
        assertThat(all.get(1).getContent()).isEqualTo("回答一");
        assertThat(all.get(2).getContent()).isEqualTo("问题二");
    }

    @Test
    void historyShouldBeQueryableViaFreshReadSimulatingRestart() {
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                OWNER, null, "", "", 0, "重启前的问题");
        historyService.completeTurn(turn, "重启前的回答", "offline-mock");

        // 模拟重启：不持有任何内存句柄，仅用 SQL 与服务只读接口重新取回
        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM chat_session WHERE session_id = ?",
                Integer.class, turn.session().getSessionId());
        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM chat_message WHERE session_id = ?",
                Integer.class, turn.session().getSessionId());
        assertThat(sessionCount).isEqualTo(1);
        assertThat(messageCount).isEqualTo(2);

        // 服务无状态，重新查询即可恢复窗口，等价于重启后按会话恢复上下文
        List<HistoryMessage> recovered = historyService.getContextWindow(turn.session().getSessionId());
        assertThat(recovered).hasSize(2);
        assertThat(recovered.get(0).getContent()).isEqualTo("重启前的问题");
    }
}
