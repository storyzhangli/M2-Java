package com.zl.d17.springai.web;

import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 历史查询控制器 Web 层测试（T07）：分页列表、消息顺序、归属校验与存在性不泄露。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryControllerWebTest extends AbstractPersistenceCleanupTest {

    private static final String BASE = "/api/v1/ai";
    private static final String HEADER = "X-Dev-User";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatHistoryService historyService;

    @Test
    void sessionsEndpointShouldReturnOwnedSessionWithPagination() throws Exception {
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                "ut_web_hist", null, "", "", 0, "Web 历史问题");
        historyService.completeTurn(turn, "Web 历史回答", "offline-mock");

        mockMvc.perform(get(BASE + "/sessions")
                        .header(HEADER, "ut_web_hist")
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[?(@.sessionId=='"
                        + turn.session().getSessionId() + "')]").exists())
                .andExpect(jsonPath("$.data.size").value(5));
    }

    @Test
    void messagesEndpointShouldReturnOrderedMessages() throws Exception {
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                "ut_web_hist", null, "", "", 0, "顺序问题");
        historyService.completeTurn(turn, "顺序回答", "offline-mock");

        mockMvc.perform(get(BASE + "/sessions/" + turn.session().getSessionId() + "/messages")
                        .header(HEADER, "ut_web_hist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].role").value("user"))
                .andExpect(jsonPath("$.data.records[1].role").value("assistant"));
    }

    @Test
    void nonOwnerAndUnknownSessionShouldGetBusinessError() throws Exception {
        ChatHistoryService.TurnStart turn = historyService.startTurn(
                "ut_web_hist", null, "", "", 0, "受保护问题");
        historyService.completeTurn(turn, "受保护回答", "offline-mock");

        // 归属不匹配：统一业务错误，不泄露会话存在性
        mockMvc.perform(get(BASE + "/sessions/" + turn.session().getSessionId() + "/messages")
                        .header(HEADER, "ut_somebody_else"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));

        // 不存在的会话：同样 4001，响应口径与归属失败一致
        mockMvc.perform(get(BASE + "/sessions/non-existent-session-id/messages")
                        .header(HEADER, "ut_web_hist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }
}
