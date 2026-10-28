package com.zl.d15.springai.conversation;

import com.zl.d15.conversation.ConversationMemory;
import com.zl.d15.conversation.RoleConversationService;
import com.zl.d15.dto.ChatRequest;
import com.zl.d15.prompt.PromptTemplateService;
import com.zl.d15.service.ChatInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双角色多轮会话编排测试：系统提示词与用户消息分离、历史累积、跨角色隔离、失败回滚与变量注入边界。
 * 全程使用内存仓库与 Mock 模型，不触达外网。
 */
@SpringBootTest
class RoleConversationServiceTest {

    @Autowired
    private RoleConversationService roleConversationService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private ConversationMemory conversationMemory;

    @Test
    void firstTurnShouldRenderSystemPromptSeparatelyFromUserMessage() {
        ChatRequest request = request("customer_service", null, "我的订单为什么还没退款？", null);
        RoleConversationService.PreparedConversation prepared = roleConversationService.prepare(request);

        ChatInvocation invocation = prepared.invocation();
        // 系统提示词来自启用模板，包含角色规则原文；用户消息只存在于 userMessage，不进入系统提示词
        assertThat(invocation.systemPrompt()).contains("在线客服代表");
        assertThat(invocation.systemPrompt()).doesNotContain("我的订单为什么还没退款？");
        assertThat(invocation.userMessage()).isEqualTo("我的订单为什么还没退款？");
        assertThat(invocation.history()).isEmpty();
        assertThat(prepared.session().getSessionId()).isNotBlank();
        assertThat(prepared.session().getScene()).isEqualTo("customer_service");

        roleConversationService.failAndRollback(prepared.session().getSessionId());
    }

    @Test
    void historyShouldAccumulateAcrossTurnsWithinSameSession() {
        RoleConversationService.PreparedConversation first =
                roleConversationService.prepare(request("programming", null, "JDK21 有什么新特性？", null));
        String sessionId = first.session().getSessionId();
        roleConversationService.completeAssistant(sessionId, "第一条助手回复");

        RoleConversationService.PreparedConversation second =
                roleConversationService.prepare(request("programming", sessionId, "再讲讲虚拟线程", null));

        assertThat(second.invocation().history()).hasSize(2);
        assertThat(second.invocation().history().get(0).role()).isEqualTo("user");
        assertThat(second.invocation().history().get(1).role()).isEqualTo("assistant");
        assertThat(second.invocation().history().get(1).content()).isEqualTo("第一条助手回复");

        roleConversationService.failAndRollback(sessionId);
        roleConversationService.failAndRollback(sessionId);
        conversationMemory.clear(sessionId);
    }

    @Test
    void sameSessionCannotSwitchRole() {
        RoleConversationService.PreparedConversation first =
                roleConversationService.prepare(request("programming", null, "先问编程问题", null));
        String sessionId = first.session().getSessionId();

        assertThatThrownBy(() ->
                roleConversationService.prepare(request("customer_service", sessionId, "再问客服问题", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("不能在同一会话切换");

        conversationMemory.clear(sessionId);
    }

    @Test
    void nonRoleSceneAndDisabledTemplateShouldBeRejected() {
        assertThatThrownBy(() ->
                roleConversationService.prepare(request("code_review", null, "审查代码", null)))
                .isInstanceOf(IllegalArgumentException.class);

        try {
            promptTemplateService.changeStatus("programming", "DISABLED");
            assertThatThrownBy(() ->
                    roleConversationService.prepare(request("programming", null, "停用后提问", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message().contains("已停用");
        } finally {
            promptTemplateService.changeStatus("programming", "ENABLED");
        }
    }

    @Test
    void failedTurnShouldRollbackUserMessageAndUnknownVariableShouldReject() {
        RoleConversationService.PreparedConversation prepared =
                roleConversationService.prepare(request("customer_service", null, "稍后会失败的提问", null));
        String sessionId = prepared.session().getSessionId();
        // 模拟模型调用失败：回滚用户消息后，同会话再次准备时历史应为空
        roleConversationService.failAndRollback(sessionId);
        RoleConversationService.PreparedConversation retry =
                roleConversationService.prepare(request("customer_service", sessionId, "重试提问", null));
        assertThat(retry.invocation().history()).isEmpty();
        conversationMemory.clear(sessionId);

        // 未声明变量键拒绝：客服模板只声明了 business_context
        assertThatThrownBy(() -> roleConversationService.prepare(
                request("customer_service", null, "带非法变量", Map.of("not_declared", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("未声明的变量");
    }

    private ChatRequest request(String scene, String sessionId, String message, Map<String, Object> variables) {
        ChatRequest req = new ChatRequest();
        req.setScene(scene);
        req.setSessionId(sessionId);
        req.setMessage(message);
        req.setVariables(variables);
        return req;
    }
}
