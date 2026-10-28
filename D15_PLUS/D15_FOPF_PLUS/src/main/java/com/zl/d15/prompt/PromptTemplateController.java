package com.zl.d15.prompt;

import com.zl.d15.common.Result;
import com.zl.d15.prompt.dto.PromptTemplateSummary;
import com.zl.d15.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d15.prompt.dto.PromptTemplateView;
import com.zl.d15.prompt.dto.TemplateStatusUpdateRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理与角色清单控制器。
 *
 * <p>全部接口沿用统一 {@link Result} 包装与全局异常处理；当前模块没有登录/权限体系，
 * 管理类写接口不做身份鉴权，该风险已在 D15 技术文档中显式标注，后续接入鉴权后在此统一加拦截。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 模板列表，支持按 scene/status 过滤与名称/templateId 关键字模糊查询。
     */
    @GetMapping("/prompts")
    public Result<List<PromptTemplateSummary>> list(
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.success(promptTemplateService.list(scene, status, keyword));
    }

    /**
     * 模板详情（含正文与变量声明）。
     */
    @GetMapping("/prompts/{templateId}")
    public Result<PromptTemplateView> detail(@PathVariable String templateId) {
        return Result.success(promptTemplateService.detail(templateId));
    }

    /**
     * 创建模板。
     */
    @PostMapping("/prompts")
    public Result<PromptTemplateView> create(@Valid @RequestBody PromptTemplateUpsertRequest request) {
        log.info("收到模板创建请求，templateId={}", request.getTemplateId());
        return Result.success(promptTemplateService.create(request));
    }

    /**
     * 更新模板（版本自增）。
     */
    @PutMapping("/prompts/{templateId}")
    public Result<PromptTemplateView> update(@PathVariable String templateId,
                                             @Valid @RequestBody PromptTemplateUpsertRequest request) {
        log.info("收到模板更新请求，templateId={}", templateId);
        return Result.success(promptTemplateService.update(templateId, request));
    }

    /**
     * 启用/停用模板。
     */
    @PatchMapping("/prompts/{templateId}/status")
    public Result<PromptTemplateView> changeStatus(@PathVariable String templateId,
                                                   @Valid @RequestBody TemplateStatusUpdateRequest request) {
        return Result.success(promptTemplateService.changeStatus(templateId, request.getStatus()));
    }

    /**
     * 逻辑删除模板。
     */
    @DeleteMapping("/prompts/{templateId}")
    public Result<Map<String, String>> delete(@PathVariable String templateId) {
        promptTemplateService.logicalDelete(templateId);
        return Result.success(Map.of("templateId", templateId, "deleted", "true"));
    }

    /**
     * 当前可对话角色清单（仅启用中的客服/编程两个角色）。
     */
    @GetMapping("/roles")
    public Result<List<PromptTemplateSummary>> roles() {
        return Result.success(promptTemplateService.listEnabledRoles());
    }
}
