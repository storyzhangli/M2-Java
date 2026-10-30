package com.zl.d17.springai.conversation;

import com.zl.d17.conversation.RoleConversationService;
import com.zl.d17.dto.ChatRequest;
import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.prompt.PromptTemplateService;
import com.zl.d17.service.ChatInvocation;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色对话服务测试（D17 版）：系统提示词与用户消息分离、多轮窗口累积、
 * 跨角色隔离、停用模板拒绝、失败留痕与变量注入边界。
 *
 * <p>全程使用 Mock 模型，不触达外网；会话与消息真实写入 MySQL，
 * 跨用例清理由 {@link AbstractPersistenceCleanupTest} 统一负责。
 * 与 D16 测试的差异：终态不再回滚删除，失败消息以 FAILED 状态保留且不进入窗口。</p>
 */
@SpringBootTest
class RoleConversationServiceTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_role";

    @Autowired
    private RoleConversationService roleConversationService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private ChatHistoryService historyService;

    @Test
    void firstTurnShouldRenderSystemPromptSeparatelyFromUserMessage() {
        ChatRequest request = request("customer_service", null, "我的订单为什么还没退款？", null);
        RoleConversationService.PreparedConversation prepared =
                roleConversationService.prepare(request, OWNER);

        ChatInvocation invocation = prepared.invocation();
        // 系统提示词来自启用模板；用户消息只存在于 userMessage，不进入系统提示词
        assertThat(invocation.systemPrompt()).contains("在线客服代表");
        assertThat(invocation.systemPrompt()).doesNotContain("我的订单为什么还没退款？");
        assertThat(invocation.userMessage()).isEqualTo("我的订单为什么还没退款？");
        assertThat(invocation.history()).isEmpty();
        assertThat(prepared.session().getSessionId()).isNotBlank();
        assertThat(prepared.session().getScene()).isEqualTo("customer_service");

        historyService.failTurn(prepared.turn(), "UtFail", "用例主动失败");
    }

    @Test
    void historyShouldAccumulateAcrossTurnsWithinSameSession() {
        RoleConversationService.PreparedConversation first = roleConversationService.prepare(
                request("programming", null, "JDK21 有什么新特性？", null), OWNER);
        String sessionId = first.session().getSessionId();
        historyService.completeTurn(first.turn(), "第一条助手回复", "offline-mock");

        RoleConversationService.PreparedConversation second = roleConversationService.prepare(
                request("programming", sessionId, "再讲讲虚拟线程", null), OWNER);

        assertThat(second.invocation().history()).hasSize(2);
        assertThat(second.invocation().history().get(0).role()).isEqualTo("user");
        assertThat(second.invocation().history().get(1).role()).isEqualTo("assistant");
        assertThat(second.invocation().history().get(1).content()).isEqualTo("第一条助手回复");

        historyService.failTurn(second.turn(), "UtFail", "用例主动失败");
    }

    @Test
    void sameSessionCannotSwitchRole() {
        RoleConversationService.PreparedConversation first = roleConversationService.prepare(
                request("programming", null, "先问编程问题", null), OWNER);
        String sessionId = first.session().getSessionId();

        assertThatThrownBy(() -> roleConversationService.prepare(
                request("customer_service", sessionId, "再问客服问题", null), OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("场景与当前请求不匹配");
    }

    @Test
    void nonRoleSceneAndDisabledTemplateShouldBeRejected() {
        assertThatThrownBy(() -> roleConversationService.prepare(
                request("code_review", null, "审查代码", null), OWNER))
                .isInstanceOf(IllegalArgumentException.class);

        try {
            promptTemplateService.changeStatus("programming", "DISABLED");
            assertThatThrownBy(() -> roleConversationService.prepare(
                    request("programming", null, "停用后提问", null), OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message().contains("模板已停用");
        } finally {
            promptTemplateService.changeStatus("programming", "ENABLED");
        }
    }

    @Test
    void failedTurnShouldKeepRecordButExcludeFromWindowAndUnknownVariableShouldReject() {
        RoleConversationService.PreparedConversation prepared = roleConversationService.prepare(
                request("customer_service", null, "稍后会失败的提问", null), OWNER);
        String sessionId = prepared.session().getSessionId();
        // 模拟模型调用失败：FAILED 消息保留，同会话再次准备时窗口为空（失败消息不进入窗口）
        historyService.failTurn(prepared.turn(), "MockFail", "模拟模型失败");
        RoleConversationService.PreparedConversation retry = roleConversationService.prepare(
                request("customer_service", sessionId, "重试提问", null), OWNER);
        assertThat(retry.invocation().history()).isEmpty();

        // 未声明变量键拒绝
        assertThatThrownBy(() -> roleConversationService.prepare(
                request("customer_service", null, "带非法变量", Map.of("not_declared", "x")), OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("未声明的变量");
    }

    private ChatRequest request(String scene, String sessionId, String message,
                                Map<String, Object> variables) {
        ChatRequest req = new ChatRequest();
        req.setScene(scene);
        req.setSessionId(sessionId);
        req.setMessage(message);
        req.setVariables(variables);
        return req;
    }
}
