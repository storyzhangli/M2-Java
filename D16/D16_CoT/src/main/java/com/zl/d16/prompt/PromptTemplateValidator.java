package com.zl.d16.prompt;

import com.zl.d16.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d16.prompt.model.PromptScenes;
import com.zl.d16.prompt.model.TemplateVariable;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板内容与变量声明校验器。
 *
 * <p>校验分三层：</p>
 * <ol>
 *     <li>基础合法性：场景取值、变量名去重、正文非空（长度由 Bean Validation 兜底）；</li>
 *     <li>占位符一致性：正文里未转义的 {@code {变量名}} 必须全部在变量声明中登记，
 *     且不允许出现无法识别的裸大括号（ST4 字面大括号必须写作 {@code \{} {@code \}}）；</li>
 *     <li>引擎试渲染：用桩值实际走一遍 Spring AI {@link PromptTemplate}，
 *     提前拦截 ST4 语法错误，避免坏模板上线后在对话链路才报错。</li>
 * </ol>
 */
@Component
public class PromptTemplateValidator {

    /** 合法占位符：大括号内为字母开头的字母数字下划线。 */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,31})}");

    /** 用于定位任何残留的未转义左大括号（合法占位符之外的裸括号）。 */
    private static final Pattern ANY_LEFT_BRACE = Pattern.compile("\\{");

    private static final char MASK_ESCAPED_OPEN = '￫';
    private static final char MASK_ESCAPED_CLOSE = '￩';

    /**
     * 校验创建/更新请求，不合法时抛 {@link IllegalArgumentException}（由全局异常处理归一为 4001）。
     */
    public void validate(PromptTemplateUpsertRequest request) {
        if (!PromptScenes.ALL_SCENES.contains(request.getScene())) {
            throw new IllegalArgumentException("scene 非法，允许的内置场景: " + PromptScenes.ALL_SCENES);
        }

        List<TemplateVariable> variables = request.getVariables() == null
                ? List.of() : request.getVariables();

        Set<String> declaredNames = new HashSet<>();
        for (TemplateVariable variable : variables) {
            if (!declaredNames.add(variable.name())) {
                throw new IllegalArgumentException("变量声明重复: " + variable.name());
            }
        }

        String content = request.getContent();
        Set<String> usedPlaceholders = extractPlaceholders(content);

        for (String used : usedPlaceholders) {
            if (!declaredNames.contains(used)) {
                throw new IllegalArgumentException(
                        "模板正文使用了未声明的变量 {" + used + "}，请先在 variables 中登记或改正文");
            }
        }

        // 合法占位符整体掩码后，若仍存在左大括号，说明是裸 JSON 括号等未转义字面量
        String escapedMasked = maskEscapedBraces(content);
        String withoutLegalPlaceholders =
                escapedMasked.replaceAll("\\{[A-Za-z][A-Za-z0-9_]{0,31}}", "");
        Matcher braceMatcher = ANY_LEFT_BRACE.matcher(withoutLegalPlaceholders);
        if (braceMatcher.find()) {
            int index = braceMatcher.start();
            String snippet = snippet(content, index);
            throw new IllegalArgumentException(
                    "模板存在未转义或非法的大括号（ST4 字面大括号必须写作 \\\\{ 与 \\\\}），出错位置附近: " + snippet);
        }

        trialRender(content, variables);
    }

    /**
     * 提取正文中全部未转义占位符名（去重、保序）。
     */
    public Set<String> extractPlaceholders(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(maskEscapedBraces(content));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 用桩值试渲染：必填变量给桩值，可选变量优先用默认值，确保引擎能完整解析整段正文。
     */
    private void trialRender(String content, List<TemplateVariable> variables) {
        Map<String, Object> stub = new HashMap<>();
        for (TemplateVariable variable : variables) {
            stub.put(variable.name(),
                    StringUtils.hasText(variable.defaultValue()) ? variable.defaultValue() : "STUB");
        }
        try {
            new PromptTemplate(content).render(stub);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("模板未通过 ST4 渲染校验: " + e.getMessage(), e);
        }
    }

    /**
     * 把转义大括号 {@code \{} {@code \}} 替换为不含括号字符的掩码，避免被占位符/裸括号规则误判。
     */
    private String maskEscapedBraces(String content) {
        return content.replace("\\{", String.valueOf(MASK_ESCAPED_OPEN))
                .replace("\\}", String.valueOf(MASK_ESCAPED_CLOSE));
    }

    private String snippet(String content, int index) {
        int start = Math.max(0, index - 10);
        int end = Math.min(content.length(), index + 11);
        return content.substring(start, end).replace("\n", "\\n");
    }
}
