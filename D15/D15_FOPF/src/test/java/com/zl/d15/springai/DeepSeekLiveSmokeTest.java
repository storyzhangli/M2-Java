package com.zl.d15.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 真实 API 在线冒烟测试。
 *
 * <p><b>默认禁用</b>：仅当同时满足以下两个环境变量条件时才启用，否则在测试发现阶段即被跳过，
 * 普通 {@code mvn test}/{@code mvn verify} 不会产生任何真实模型调用或费用：</p>
 * <ul>
 *     <li>{@code AI_MODE=deepseek}；</li>
 *     <li>{@code DEEPSEEK_API_KEY} 形如 {@code sk-...} 的真实密钥（占位串 d01-mock-... 不匹配）。</li>
 * </ul>
 *
 * <p>手动启用示例（PowerShell）：</p>
 * <pre>
 * $env:AI_MODE="deepseek"
 * $env:DEEPSEEK_API_KEY="sk-你的真实密钥"
 * mvn -f D01\pom.xml test -Dtest=DeepSeekLiveSmokeTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AI_MODE", matches = "deepseek")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = "sk-.+")
@SpringBootTest
class DeepSeekLiveSmokeTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void realDeepSeekCallShouldReturnNonBlankReply() {
        Prompt prompt = new Prompt(new UserMessage("用一句话说明设备日常点检的作用。"));
        ChatResponse response = chatModel.call(prompt);

        String reply = response.getResult().getOutput().getText();
        assertThat(reply).isNotBlank();
        System.out.println("[LiveSmoke] DeepSeek 真实回复摘要（前 80 字）: "
                + reply.substring(0, Math.min(80, reply.length())));
    }
}
