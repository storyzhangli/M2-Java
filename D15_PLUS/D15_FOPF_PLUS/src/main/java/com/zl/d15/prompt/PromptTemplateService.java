package com.zl.d15.prompt;

import com.zl.d15.prompt.dto.PromptTemplateSummary;
import com.zl.d15.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d15.prompt.dto.PromptTemplateView;
import com.zl.d15.prompt.model.PromptScenes;
import com.zl.d15.prompt.model.PromptTemplateEntity;
import com.zl.d15.prompt.model.TemplateStatus;
import com.zl.d15.prompt.model.TemplateVariable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prompt 模板领域服务：模板生命周期管理、启停/逻辑删除、变量渲染与对话选用校验。
 *
 * <p>写操作在服务层加锁（管理端低频场景，优先正确性）；读操作基于内存仓库的快照遍历。
 * 模型调用方只允许通过 {@link #resolveEnabledRoleTemplate(String)} 取得启用模板，
 * 已停用/已删除/非角色场景一律快速失败。</p>
 */
@Slf4j
@Service
public class PromptTemplateService {

    private final PromptTemplateRepository repository;

    private final PromptTemplateValidator validator;

    public PromptTemplateService(PromptTemplateRepository repository,
                                 PromptTemplateValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /**
     * 列表查询，可按场景、状态、名称关键字过滤；参数为空表示不过滤。
     */
    public List<PromptTemplateSummary> list(String scene, String status, String keyword) {
        return repository.findAllActive().stream()
                .filter(e -> !StringUtils.hasText(scene) || scene.equals(e.getScene()))
                .filter(e -> !StringUtils.hasText(status) || matchesStatus(e, status))
                .filter(e -> !StringUtils.hasText(keyword)
                        || (e.getTemplateId().contains(keyword) || e.getName().contains(keyword)))
                .map(PromptTemplateSummary::from)
                .toList();
    }

    /**
     * 查询详情，不存在或已逻辑删除时按参数错误返回（业务码 4001）。
     */
    public PromptTemplateView detail(String templateId) {
        return PromptTemplateView.from(requireActive(templateId));
    }

    /**
     * 创建模板。
     */
    public synchronized PromptTemplateView create(PromptTemplateUpsertRequest request) {
        validator.validate(request);
        if (repository.findAnyById(request.getTemplateId()).isPresent()) {
            throw new IllegalArgumentException("templateId 已存在（含已删除模板），不能重复创建: "
                    + request.getTemplateId());
        }
        LocalDateTime now = LocalDateTime.now();
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setTemplateId(request.getTemplateId());
        applyUpsertFields(entity, request);
        entity.setVersion(1);
        entity.setStatus(TemplateStatus.ENABLED);
        entity.setDeleted(false);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.save(entity);
        log.info("Prompt 模板已创建，templateId={}，scene={}，变量数={}",
                entity.getTemplateId(), entity.getScene(), entity.getVariables().size());
        return PromptTemplateView.from(entity);
    }

    /**
     * 更新模板内容/变量，版本号自增；templateId、状态、删除标记与创建时间保持不变。
     */
    public synchronized PromptTemplateView update(String pathTemplateId,
                                                  PromptTemplateUpsertRequest request) {
        PromptTemplateEntity entity = requireActive(pathTemplateId);
        if (StringUtils.hasText(request.getTemplateId())
                && !pathTemplateId.equals(request.getTemplateId())) {
            throw new IllegalArgumentException("路径 templateId 与请求体 templateId 不一致，模板稳定标识不允许修改");
        }
        validator.validate(request);

        applyUpsertFields(entity, request);
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        log.info("Prompt 模板已更新，templateId={}，新版本 version={}", entity.getTemplateId(), entity.getVersion());
        return PromptTemplateView.from(entity);
    }

    /**
     * 启用/停用切换。
     */
    public synchronized PromptTemplateView changeStatus(String templateId, String statusText) {
        PromptTemplateEntity entity = requireActive(templateId);
        TemplateStatus target;
        try {
            target = TemplateStatus.valueOf(statusText.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("status 非法，仅支持 ENABLED 或 DISABLED: " + statusText);
        }
        entity.setStatus(target);
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        log.info("Prompt 模板状态变更，templateId={}，status={}", templateId, target);
        return PromptTemplateView.from(entity);
    }

    /**
     * 逻辑删除：仅置删除标志，不物理移除，保证历史会话引用仍可追溯。
     */
    public synchronized void logicalDelete(String templateId) {
        PromptTemplateEntity entity = requireActive(templateId);
        entity.setDeleted(true);
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        log.info("Prompt 模板已逻辑删除，templateId={}", templateId);
    }

    /**
     * 角色对话入口：取得可用于模型调用的启用模板。
     * 非角色场景、停用、删除、不存在均拒绝，防止前端绕过传入任意标识。
     */
    public PromptTemplateEntity resolveEnabledRoleTemplate(String scene) {
        if (!PromptScenes.CHAT_ROLE_SCENES.contains(scene)) {
            throw new IllegalArgumentException("scene 不是当前开放的对话角色: " + scene
                    + "，允许值: " + PromptScenes.CHAT_ROLE_SCENES);
        }
        PromptTemplateEntity entity = repository.findAllActive().stream()
                .filter(e -> scene.equals(e.getScene()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("场景对应模板不存在: " + scene));
        if (entity.getStatus() != TemplateStatus.ENABLED) {
            throw new IllegalArgumentException("模板已停用，不能发起新对话，templateId=" + entity.getTemplateId());
        }
        return entity;
    }

    /**
     * 按变量声明渲染模板正文。
     *
     * <p>安全规则：入参键必须是已声明变量，拒绝未声明的注入键；必填变量缺失且无默认值即失败；
     * 用户动态值只作为 ST 变量数据传入，永远不会被拼进系统规则位置。</p>
     *
     * @return 渲染后的系统提示词
     */
    public String render(PromptTemplateEntity entity, Map<String, Object> inputs) {
        Map<String, Object> safeInputs = inputs == null ? Map.of() : inputs;
        Map<String, TemplateVariable> declared = new HashMap<>();
        entity.getVariables().forEach(v -> declared.put(v.name(), v));

        for (String key : safeInputs.keySet()) {
            if (!declared.containsKey(key)) {
                throw new IllegalArgumentException("传入了模板未声明的变量: " + key);
            }
        }

        Map<String, Object> resolved = new HashMap<>();
        for (TemplateVariable variable : entity.getVariables()) {
            Object raw = safeInputs.get(variable.name());
            boolean blank = raw == null || !StringUtils.hasText(String.valueOf(raw));
            if (blank) {
                if (variable.required() && !StringUtils.hasText(variable.defaultValue())) {
                    throw new IllegalArgumentException("缺少必填模板变量: " + variable.name());
                }
                resolved.put(variable.name(),
                        StringUtils.hasText(variable.defaultValue()) ? variable.defaultValue() : "");
            } else {
                resolved.put(variable.name(), String.valueOf(raw));
            }
        }

        try {
            return new PromptTemplate(entity.getContent()).render(resolved);
        } catch (RuntimeException e) {
            // 保存期已做试渲染，这里再兜底一次，错误信息不回显用户输入全文
            throw new IllegalArgumentException("模板渲染失败，templateId=" + entity.getTemplateId()
                    + "，原因: " + e.getMessage());
        }
    }

    /**
     * 返回启用中的角色模板列表（供 GET /roles 与前端角色选择器使用）。
     */
    public List<PromptTemplateSummary> listEnabledRoles() {
        return repository.findAllActive().stream()
                .filter(e -> PromptScenes.CHAT_ROLE_SCENES.contains(e.getScene()))
                .filter(e -> e.getStatus() == TemplateStatus.ENABLED)
                .map(PromptTemplateSummary::from)
                .toList();
    }

    private boolean matchesStatus(PromptTemplateEntity entity, String statusText) {
        try {
            return entity.getStatus() == TemplateStatus.valueOf(statusText.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("status 查询参数非法，仅支持 ENABLED 或 DISABLED: " + statusText);
        }
    }

    private PromptTemplateEntity requireActive(String templateId) {
        return repository.findActiveById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在或已删除: " + templateId));
    }

    private void applyUpsertFields(PromptTemplateEntity entity, PromptTemplateUpsertRequest request) {
        entity.setName(request.getName());
        entity.setScene(request.getScene());
        entity.setDescription(request.getDescription());
        entity.setContent(request.getContent());
        entity.setVariables(request.getVariables() == null ? List.of() : request.getVariables());
    }

    /** 仅供需要读取占位符名的上层（如测试/诊断）复用同一套解析规则。 */
    public Set<String> extractPlaceholders(String content) {
        return validator.extractPlaceholders(content);
    }
}
