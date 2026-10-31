package com.zl.d17.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d17.prompt.po.PromptTemplatePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * Prompt 模板表 Mapper。
 *
 * <p>沿用项目已引入的 MyBatis-Plus 3.5.x 规范，单表 CRUD 直接由 {@link BaseMapper} 提供，
 * 本迭代没有复杂多表查询，不额外编写 XML；@Mapper 注解保证无需在启动类扩大扫描范围。</p>
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplatePO> {
}
