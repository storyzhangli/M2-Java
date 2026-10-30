package com.zl.d17.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 模板启用/停用请求体。
 */
@Data
public class TemplateStatusUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标状态，取值 ENABLED / DISABLED（大小写不敏感由服务层处理）。 */
    @NotBlank(message = "status 不能为空，取值 ENABLED 或 DISABLED")
    private String status;
}
