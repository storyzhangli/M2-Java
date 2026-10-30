package com.zl.d17.service;

import java.util.Arrays;

/**
 * 推理强度白名单枚举（Reasoning Effort，推理努力程度）。
 *
 * <p>取值口径与 DeepSeek 官方《思考模式》文档中的 {@code reasoning_effort=low/high/max} 对齐，
 * 仅作为本应用 DTO 层的服务端白名单与未来升级预留契约。<b>是否能真正下发给模型，还取决于当前
 * Spring AI 客户端版本是否提供对应请求字段</b>：经核验 Spring AI 1.1.8 的
 * {@code DeepSeekApi.ChatCompletionRequest} 与 {@code DeepSeekChatOptions} 均无 thinking/effort 字段，
 * 因此当前版本由 {@link ThinkingSettingsResolver} 显式拒绝该参数，而不是静默丢弃。</p>
 */
public enum ReasoningEffort {

    /** 低强度推理：更短的内部思维链，延迟与 token 开销更低。 */
    LOW,

    /** 高强度推理：官方默认档位，深度与开销均衡。 */
    HIGH,

    /** 最大强度推理：最长思维链，成本与延迟最高。 */
    MAX;

    /**
     * 按白名单解析前端传入的强度字符串（大小写不敏感）。
     *
     * @param raw 原始入参，blank 由调用方先判空
     * @return 命中的枚举值
     * @throws IllegalArgumentException 取值不在白名单时抛出，由全局异常处理器归一为 4001
     */
    public static ReasoningEffort parseWhitelisted(String raw) {
        String normalized = raw.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(v -> v.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "reasoningEffort 取值非法，仅支持 LOW / HIGH / MAX，实际收到: " + raw));
    }
}
