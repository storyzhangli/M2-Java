package com.zl.d17.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 聊天接口请求体。
 */
@Data
public class ChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 注解层面的绝对长度上限，防止绕过业务可配置上限的超长请求。 */
    public static final int HARD_MAX_MESSAGE_LENGTH = 2000;

    /**
     * 用户提问内容。
     *
     * <p>{@code @NotBlank} 拒绝 null/空串/纯空白；{@code @Size} 提供硬性长度天花板，
     * 业务可配置上限（{@code app.ai.max-message-length}）在 Controller 中再次校验。</p>
     */
    @NotBlank(message = "message 不能为空或纯空白")
    @Size(max = HARD_MAX_MESSAGE_LENGTH, message = "message 长度不能超过 " + HARD_MAX_MESSAGE_LENGTH + " 个字符")
    private String message;

    /**
     * D15 角色场景标识（可选）。为空时走旧的无角色链路；填写后必须是开放角色
     * （customer_service / programming）且对应模板处于启用状态，由服务端校验，前端显示名不作数。
     */
    @Size(max = 64, message = "scene 长度不能超过64个字符")
    private String scene;

    /**
     * 多轮会话标识（可选）。为空时服务端新建会话并在响应中返回；同一 sessionId 绑定唯一角色，
     * 换角色必须传新的 sessionId。
     */
    @Size(max = 64, message = "sessionId 长度不能超过64个字符")
    private String sessionId;

    /**
     * 模板变量入参（可选）。键必须是所选模板声明过的变量，未声明键由服务端拒绝，
     * 不允许通过变量位注入系统规则。
     */
    private Map<String, Object> variables;

    /**
     * D16 思考模式开关（可选）。{@code null}/false 表示旧客户端默认行为（普通模型链路）；
     * 是否真正生效由服务端按当前模型客户端能力裁决，前端标签本身不作数。
     */
    private Boolean thinkingEnabled;

    /**
     * D16 推理强度（可选），白名单取值 LOW/HIGH/MAX，大小写不敏感。
     * 仅在 thinkingEnabled=true 时有意义；当前 Spring AI 1.1.8 无法下发该参数，
     * 服务端会显式拒绝而非静默吞参，具体规则见 ThinkingSettingsResolver。
     */
    @Size(max = 16, message = "reasoningEffort 长度不能超过16个字符")
    private String reasoningEffort;

    /**
     * D16 是否受控展示模型原始推理过程（可选）。默认 false：reasoning_content 不随普通回答返回、
     * 不写入会话记忆与业务日志；仅当 thinkingEnabled=true 且本字段为 true 时才在独立字段/事件中返回。
     */
    private Boolean showReasoning;

    /**
     * D16 是否启用知识库检索与引用来源标注（可选）。默认 false，走与旧版本一致的普通问答；
     * 显式为 true 时才会执行请求级真实检索，引用只能来自本轮实际命中的知识片段。
     */
    private Boolean retrieveEnabled;
}
