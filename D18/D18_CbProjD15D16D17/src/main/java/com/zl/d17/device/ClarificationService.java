package com.zl.d17.device;

import com.zl.d17.device.model.ConfidenceLevel;
import com.zl.d17.device.model.DeviceInformation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 缺失信息澄清策略与不确定性表达服务（D18）。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>按优先级识别缺失信息，每轮只产出一个最高价值澄清问题，不一次问全；</li>
 *   <li>不确定性只用定性等级并明确标注“内部定性判断、非概率”，不输出伪造百分比；</li>
 *   <li>本阶段不接知识库检索，无来源时如实声明，绝不伪造引用编号；</li>
 *   <li>命中高危症状时给出安全提示，不臆造处置步骤。</li>
 * </ul>
 */
@Component
public class ClarificationService {

    /** 本阶段不启用知识库检索，因此不存在可核验引用来源。 */
    private static final boolean KNOWLEDGE_SOURCE_AVAILABLE = false;

    private static final String NO_SOURCE_NOTE =
            "当前未检索到可核验的知识来源，以下为基于已提供信息的澄清，不引用未确认资料。";

    /** 高危症状（规范名）。 */
    private static final Set<String> HIGH_RISK_SYMPTOMS = Set.of("冒烟/异味");

    /**
     * 澄清结果。
     *
     * @param clarificationRequired  是否需要澄清
     * @param missingInformation     缺失信息清单（按优先级）
     * @param clarificationQuestion  单条澄清问题（无需澄清时为 null）
     * @param uncertaintyNote        不确定性与来源说明
     * @param confidenceLevel        内部定性置信等级
     * @param highRisk               是否命中高危情形
     */
    public record ClarificationResult(
            boolean clarificationRequired,
            List<String> missingInformation,
            String clarificationQuestion,
            String uncertaintyNote,
            ConfidenceLevel confidenceLevel,
            boolean highRisk
    ) {
    }

    /**
     * 评估设备信息档案并生成澄清结果。
     */
    public ClarificationResult evaluate(DeviceInformation info) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(info.getDeviceModel())) {
            missing.add("设备型号");
        }
        if (!StringUtils.hasText(info.getFaultCode())) {
            missing.add("故障码");
        }
        if (info.getObservedSymptoms().isEmpty()) {
            missing.add("具体故障现象");
        }
        if (!StringUtils.hasText(info.getOccurrenceInfo())) {
            missing.add("故障发生时间或频率");
        }
        if (!StringUtils.hasText(info.getDeviceSerial())) {
            missing.add("设备编号或产线位置");
        }

        boolean needClarify = !missing.isEmpty();
        String question = needClarify ? buildQuestion(missing.get(0)) : null;
        ConfidenceLevel level = resolveLevel(info);
        boolean highRisk = info.getObservedSymptoms().stream().anyMatch(HIGH_RISK_SYMPTOMS::contains);

        String note = buildUncertaintyNote(missing, level, highRisk);
        return new ClarificationResult(needClarify, List.copyOf(missing), question, note, level, highRisk);
    }

    private String buildQuestion(String missingField) {
        return switch (missingField) {
            case "设备型号" -> "请先提供设备的具体型号（通常在设备铭牌或型号标签上），以便准确匹配故障资料。";
            case "故障码" -> "请提供控制面板上显示的故障码或报警代码（例如 E101、F203），便于定位故障类型。";
            case "具体故障现象" -> "请描述具体的故障现象，例如是否有报警、异响、过热、振动或停机等可观察情况。";
            case "故障发生时间或频率" -> "请说明故障是什么时候开始的、是持续出现还是间歇发生、大约多久一次。";
            default -> "请提供设备编号或所在产线/工位，便于现场核对具体设备。";
        };
    }

    private ConfidenceLevel resolveLevel(DeviceInformation info) {
        boolean modelPresent = StringUtils.hasText(info.getDeviceModel());
        boolean codePresent = StringUtils.hasText(info.getFaultCode());
        boolean symptomsPresent = !info.getObservedSymptoms().isEmpty();
        if (modelPresent && codePresent && symptomsPresent) {
            return ConfidenceLevel.HIGH;
        }
        return modelPresent ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
    }

    private String buildUncertaintyNote(List<String> missing, ConfidenceLevel level, boolean highRisk) {
        StringBuilder builder = new StringBuilder(NO_SOURCE_NOTE);
        builder.append(" 当前把握程度为“").append(level.name()).append("”（内部定性判断，非概率、非百分比）。");
        if (!missing.isEmpty()) {
            builder.append(" 仍待确认：").append(String.join("、", missing)).append("。");
        }
        if (highRisk) {
            builder.append(" 检测到冒烟/异味等高危情形，请先按现场安全规程断电并保持安全距离，"
                    + "由具备资质人员处理，切勿自行拆解或带电操作。");
        }
        return builder.toString();
    }
}
