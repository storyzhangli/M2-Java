package com.zl.d17.device;

import com.zl.d17.device.dto.FaultTicketCandidate;
import com.zl.d17.device.model.CandidateStatus;
import com.zl.d17.device.model.DeviceInformation;
import com.zl.d17.device.model.InformationSource;
import com.zl.d17.device.model.TicketCategory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 故障工单候选确定性构建服务（D18）。
 *
 * <p>基于设备信息档案与澄清结果，用规则映射生成候选 DTO，不调用模型、不写正式表。
 * 分类仅在症状具有足够指向性时给出具体类别，通用症状一律 {@link TicketCategory#UNKNOWN}，
 * 避免强行归类。构建后通过 Bean Validation 校验，违例即抛、不静默截断。</p>
 */
@Component
public class FaultTicketCandidateService {

    /** 规范症状 → 工单分类的确定性映射（通用症状映射为 UNKNOWN）。 */
    private static final Map<String, TicketCategory> CATEGORY_RULES = Map.of(
            "冒烟/异味", TicketCategory.SAFETY,
            "泄漏", TicketCategory.COOLING,
            "压力异常", TicketCategory.COOLING,
            "过热/温度异常", TicketCategory.SENSOR,
            "过载", TicketCategory.CONTROL,
            "异常振动", TicketCategory.MECHANICAL,
            "异响", TicketCategory.MECHANICAL,
            "机械卡滞", TicketCategory.MECHANICAL,
            "停机/无法启动", TicketCategory.UNKNOWN,
            "报警", TicketCategory.UNKNOWN
    );

    private final Validator validator;

    public FaultTicketCandidateService(Validator validator) {
        this.validator = validator;
    }

    /**
     * 构建故障工单候选。
     *
     * @param info          设备信息档案
     * @param clarification 澄清结果
     */
    public FaultTicketCandidate build(DeviceInformation info,
                                      ClarificationService.ClarificationResult clarification) {
        List<String> symptoms = new ArrayList<>(info.getObservedSymptoms());
        TicketCategory category = resolveCategory(symptoms);

        FaultTicketCandidate candidate = new FaultTicketCandidate(
                buildSummary(info, symptoms),
                category,
                emptyToNull(info.getDeviceModel()),
                emptyToNull(info.getFaultCode()),
                List.copyOf(symptoms),
                clarification.missingInformation(),
                clarification.uncertaintyNote(),
                clarification.clarificationRequired()
                        ? CandidateStatus.NEEDS_CLARIFICATION : CandidateStatus.CANDIDATE,
                InformationSource.USER_STATED
        );
        validate(candidate);
        return candidate;
    }

    /** 按映射顺序取第一个具体类别；只有通用症状或无映射时为 UNKNOWN。 */
    private TicketCategory resolveCategory(List<String> symptoms) {
        TicketCategory resolved = TicketCategory.UNKNOWN;
        for (String symptom : symptoms) {
            TicketCategory mapped = CATEGORY_RULES.get(symptom);
            if (mapped == null) {
                continue;
            }
            if (mapped != TicketCategory.UNKNOWN) {
                return mapped;
            }
            resolved = mapped;
        }
        return resolved;
    }

    /** 基于事实构建摘要，不补充未提供的信息。 */
    private String buildSummary(DeviceInformation info, List<String> symptoms) {
        StringBuilder builder = new StringBuilder("设备 ");
        builder.append(StringUtils.hasText(info.getDeviceModel()) ? info.getDeviceModel() : "型号未知");
        if (StringUtils.hasText(info.getFaultCode())) {
            builder.append("，故障码 ").append(info.getFaultCode());
        }
        if (symptoms.isEmpty()) {
            builder.append("，用户尚未描述具体故障现象（待补充）");
        } else {
            builder.append("，现象：").append(symptoms.stream().collect(Collectors.joining("、")));
        }
        return builder.toString();
    }

    private void validate(FaultTicketCandidate candidate) {
        Set<ConstraintViolation<FaultTicketCandidate>> violations = validator.validate(candidate);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("工单候选字段校验失败: " + detail);
        }
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
