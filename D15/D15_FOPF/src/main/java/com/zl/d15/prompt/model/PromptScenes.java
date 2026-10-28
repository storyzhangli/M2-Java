package com.zl.d15.prompt.model;

import java.util.Set;

/**
 * Prompt 场景标识常量与角色范围定义。
 *
 * <p>场景标识同时作为模板的稳定业务键之一；其中两个场景在本迭代中开放为可对话角色，
 * 其余场景提供可用种子模板与完整管理能力，后续迭代可平滑开放为角色。</p>
 */
public final class PromptScenes {

    /** 客服对话（本迭代开放的可对话角色）。 */
    public static final String CUSTOMER_SERVICE = "customer_service";

    /** 编程助手（本迭代开放的可对话角色）。 */
    public static final String PROGRAMMING = "programming";

    /** 技术知识解释。 */
    public static final String TECHNICAL_EXPLANATION = "technical_explanation";

    /** 代码审查。 */
    public static final String CODE_REVIEW = "code_review";

    /** 文本总结。 */
    public static final String SUMMARIZATION = "summarization";

    /** 结构化信息提取。 */
    public static final String INFORMATION_EXTRACTION = "information_extraction";

    /**
     * 本迭代允许在角色对话接口中选择的场景集合。
     * 不在集合内的场景只能通过模板管理 API 维护，不能直接发起角色对话。
     */
    public static final Set<String> CHAT_ROLE_SCENES = Set.of(CUSTOMER_SERVICE, PROGRAMMING);

    /** 全部内置场景，用于创建/更新模板时的场景值合法性校验。 */
    public static final Set<String> ALL_SCENES = Set.of(
            CUSTOMER_SERVICE,
            PROGRAMMING,
            TECHNICAL_EXPLANATION,
            CODE_REVIEW,
            SUMMARIZATION,
            INFORMATION_EXTRACTION
    );

    private PromptScenes() {
    }
}
