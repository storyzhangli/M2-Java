package com.zl.d16.service;

import com.zl.d16.dto.ChatRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 思考模式请求参数解析器（服务端白名单校验的唯一入口）。
 *
 * <p>核心原则：</p>
 * <ul>
 *     <li>前端只能表达“意图”，最终可用设置由服务端按依赖真实能力裁决；</li>
 *     <li>所有新参数均可缺省，缺省即 {@link ThinkingSettings#legacyDefault()}，旧客户端零改造；</li>
 *     <li>当前 Spring AI 1.1.8 的 DeepSeek 请求体不具备 thinking/reasoning_effort 字段
 *     （已对本地依赖 jar 反编译核验），显式传入推理强度时必须明确拒绝，
 *     禁止“参数被悄悄丢弃却仍对客户端宣称已启用”。</li>
 * </ul>
 */
@Component
public class ThinkingSettingsResolver {

    /**
     * 当前客户端是否支持把推理强度真正下发给模型。
     *
     * <p>固定为 false 的依据：spring-ai-deepseek 1.1.8 的 {@code DeepSeekApi.ChatCompletionRequest}
     * 与 {@code DeepSeekChatOptions} 均无 thinking/reasoningEffort 字段。升级支持该字段的 Spring AI
     * 版本并完成实测后，才可将本能力开关外置为配置并置 true。</p>
     */
    public static final boolean REASONING_EFFORT_TRANSMITTABLE = false;

    /**
     * 解析并校验一次请求的思考设置。
     *
     * @param request 聊天请求 DTO
     * @return 服务器权威的思考设置
     * @throws IllegalArgumentException 强度非法、关闭状态下携带强度、或当前版本不支持下发强度时抛出（归一 4001）
     */
    public ThinkingSettings resolve(ChatRequest request) {
        boolean enabled = Boolean.TRUE.equals(request.getThinkingEnabled());
        boolean showReasoning = Boolean.TRUE.equals(request.getShowReasoning());

        ReasoningEffort effort = null;
        if (StringUtils.hasText(request.getReasoningEffort())) {
            // 先做白名单校验，非法取值直接拒绝，避免任意字符串流入模型适配层
            effort = ReasoningEffort.parseWhitelisted(request.getReasoningEffort());

            if (!enabled) {
                throw new IllegalArgumentException(
                        "reasoningEffort 仅在 thinkingEnabled=true 时允许传入，请先显式启用思考模式");
            }
            if (!REASONING_EFFORT_TRANSMITTABLE) {
                // 显式拒绝而非静默忽略：客户端能明确感知当前客户端版本的能力边界
                throw new IllegalArgumentException(
                        "当前 Spring AI 1.1.8 DeepSeek 客户端不支持下发 reasoning_effort/thinking 参数"
                                + "（已核验请求体无对应字段），思考模式仅支持通过模型切换 deepseek-reasoner 开关；"
                                + "请仅传 thinkingEnabled，或升级客户端版本后再使用推理强度");
            }
        }

        // 未启用思考时，展示开关没有可展示的对象，归一为 false 以免前端误判
        if (!enabled) {
            showReasoning = false;
        }
        return new ThinkingSettings(enabled, effort, showReasoning);
    }
}
