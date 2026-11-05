package com.zl.d23;

import com.zl.d23.history.ChatHistoryService;
import com.zl.d23.history.model.HistoryMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮上下文窗口回归测试（D23-R03）。
 *
 * <p>验证消息按“正确会话、正确顺序”进入下一轮上下文：新会话空历史、完成轮次按
 * 序号正序入窗、PENDING/FAILED 不入窗、同会话续接与跨会话独立。断言真实内容与顺序，
 * 而非仅验证方法被调用。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class MultiTurnContextWindowTest {

    @Autowired
    private ChatHistoryService historyService;

    @Test
    @DisplayName("R03 新会话首轮开始时上下文为空（PENDING 不入窗）")
    void newSession_firstPending_contextEmpty() {
        String owner = "ut_mt_owner_1";
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                owner, null, "", "", 0, "第一轮问题");

        List<HistoryMessage> window =
                historyService.getContextWindow(turn.session().getSessionId());

        assertThat(window).isEmpty();
        historyService.completeTurn(turn, "第一轮回答", "offline-mock");
    }

    @Test
    @DisplayName("R03 完成一轮后上下文按正序包含 user+assistant")
    void completedTurn_entersWindowInOrder() {
        String owner = "ut_mt_owner_2";
        ChatHistoryService.TurnStart turn1 = historyService.startTurn(
                owner, null, "", "", 0, "问题一");
        historyService.completeTurn(turn1, "回答一", "offline-mock");

        List<HistoryMessage> window =
                historyService.getContextWindow(turn1.session().getSessionId());

        assertThat(window).hasSize(2);
        assertThat(window).extracting(HistoryMessage::getRole)
                .containsExactly(ChatHistoryService.ROLE_USER, ChatHistoryService.ROLE_ASSISTANT);
        assertThat(window).extracting(HistoryMessage::getContent)
                .containsExactly("问题一", "回答一");
        assertThat(window).extracting(HistoryMessage::getSequenceNo)
                .isSorted();
    }

    @Test
    @DisplayName("R03 同会话第二轮续接：窗口含两轮且角色交替、序号递增")
    void secondTurn_sameSession_appendsInOrder() {
        String owner = "ut_mt_owner_3";
        ChatHistoryService.TurnStart turn1 = historyService.startTurn(
                owner, null, "", "", 0, "问题一");
        historyService.completeTurn(turn1, "回答一", "offline-mock");

        String sessionId = turn1.session().getSessionId();
        ChatHistoryService.TurnStart turn2 = historyService.startTurn(
                owner, sessionId, "", "", 0, "问题二");
        // 第二轮 PENDING 期间，窗口仍只含第一轮 COMPLETED
        assertThat(historyService.getContextWindow(sessionId)).hasSize(2);
        historyService.completeTurn(turn2, "回答二", "offline-mock");

        List<HistoryMessage> window = historyService.getContextWindow(sessionId);

        assertThat(window).hasSize(4);
        assertThat(window).extracting(HistoryMessage::getContent)
                .containsExactly("问题一", "回答一", "问题二", "回答二");
        assertThat(window).extracting(HistoryMessage::getRole)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(window).extracting(HistoryMessage::getSequenceNo)
                .isSorted();
        assertThat(window).allSatisfy(m -> assertThat(m.getSessionId()).isEqualTo(sessionId));
    }

    @Test
    @DisplayName("R03 失败轮次不进入上下文窗口")
    void failedTurn_excludedFromWindow() {
        String owner = "ut_mt_owner_4";
        ChatHistoryService.TurnStart turn1 = historyService.startTurn(
                owner, null, "", "", 0, "成功问题");
        historyService.completeTurn(turn1, "成功回答", "offline-mock");

        String sessionId = turn1.session().getSessionId();
        ChatHistoryService.TurnStart failed = historyService.startTurn(
                owner, sessionId, "", "", 0, "失败问题");
        historyService.failTurn(failed, "RuntimeException", "模拟失败");

        List<HistoryMessage> window = historyService.getContextWindow(sessionId);

        assertThat(window).hasSize(2);
        assertThat(window).extracting(HistoryMessage::getContent)
                .containsExactly("成功问题", "成功回答");
        assertThat(window).noneMatch(m -> "失败问题".equals(m.getContent()));
    }

    @Test
    @DisplayName("R03 不同会话上下文互不串线")
    void differentSessions_haveIndependentContext() {
        String owner = "ut_mt_owner_5";
        ChatHistoryService.TurnStart sessionA = historyService.startTurn(
                owner, null, "", "", 0, "会话A问题");
        historyService.completeTurn(sessionA, "会话A回答", "offline-mock");

        ChatHistoryService.TurnStart sessionB = historyService.startTurn(
                owner, null, "", "", 0, "会话B问题");
        historyService.completeTurn(sessionB, "会话B回答", "offline-mock");

        List<HistoryMessage> windowB =
                historyService.getContextWindow(sessionB.session().getSessionId());

        assertThat(windowB).extracting(HistoryMessage::getContent)
                .containsExactly("会话B问题", "会话B回答");
        assertThat(windowB).noneMatch(m -> "会话A问题".equals(m.getContent()));
    }
}
