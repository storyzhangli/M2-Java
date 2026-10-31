package com.zl.d17.device;

import com.zl.d17.device.model.DeviceInformation;
import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.model.HistoryMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备信息确定性提取器（D18）。
 *
 * <p>基于关键词与正则从用户消息中提取设备型号、故障码、编号、发生信息与可观察症状，
 * 不调用模型、不产生网络请求，结果离线可重复。每轮按“历史用户消息 → 当前消息”顺序合并，
 * 已提供的信息保留，空白不覆盖。</p>
 */
@Component
public class DeviceInformationExtractor {

    /** 显式设备型号：型号/机型/model 后接编码。 */
    private static final Pattern EXPLICIT_MODEL = Pattern.compile(
            "(?:型号|机型|设备型号|model)[是为：:\\s#]*([A-Za-z0-9][A-Za-z0-9_\\-]{1,31})",
            Pattern.CASE_INSENSITIVE);

    /** 独立设备型号编码：常见前缀-数字字母。 */
    private static final Pattern STANDALONE_MODEL = Pattern.compile(
            "\\b(?:CNC|EQ|DEV|MC|SV|SP)[ _-]?[A-Za-z0-9]{1,20}\\b");

    /** 显式故障码：故障码/报警代码/错误码等后接编码。 */
    private static final Pattern EXPLICIT_FAULT_CODE = Pattern.compile(
            "(?:故障码|故障代码|报警代码|报错代码|错误码|错误代码|报警码)[是为：:\\s#]*([A-Za-z]?\\d{1,6}[A-Za-z0-9_\\-]*)");

    /** 独立故障码：E/F 后接 2 位以上数字。 */
    private static final Pattern STANDALONE_FAULT_CODE = Pattern.compile(
            "\\b([EF])\\s?-?(\\d{2,6})\\b");

    /** 设备编号/序列号/产线。 */
    private static final Pattern SERIAL = Pattern.compile(
            "(?:序列号|设备编号|设备序号|编号|产线|工位)[是为：:\\s#]*([A-Za-z0-9][A-Za-z0-9_\\-]{1,31})");

    /** 时长描述。 */
    private static final Pattern DURATION = Pattern.compile(
            "\\d+\\s*(?:分钟|小时|天|秒)");

    /** 症状关键词 → 规范症状名（保序，先匹配先生效）。 */
    private static final Map<Pattern, String> SYMPTOM_RULES = new LinkedHashMap<>();

    static {
        SYMPTOM_RULES.put(Pattern.compile("冒烟|焦糊|烧焦|异味"), "冒烟/异味");
        SYMPTOM_RULES.put(Pattern.compile("泄漏|漏油|漏水|漏液|渗油|渗液"), "泄漏");
        SYMPTOM_RULES.put(Pattern.compile("过热|发烫|温度高|温升|高温"), "过热/温度异常");
        SYMPTOM_RULES.put(Pattern.compile("振动|抖动|震动"), "异常振动");
        SYMPTOM_RULES.put(Pattern.compile("异响|噪音|噪声|声音大|响声"), "异响");
        SYMPTOM_RULES.put(Pattern.compile("卡滞|卡死|卡住|卡顿"), "机械卡滞");
        SYMPTOM_RULES.put(Pattern.compile("过载|超载"), "过载");
        SYMPTOM_RULES.put(Pattern.compile("压力|泄压"), "压力异常");
        SYMPTOM_RULES.put(Pattern.compile("停机|停了|无法启动|开不了机|起不来"), "停机/无法启动");
        SYMPTOM_RULES.put(Pattern.compile("报警|告警|报错"), "报警");
    }

    /** 时间/频率关键词。 */
    private static final List<String> TIME_WORDS =
            List.of("今天", "今早", "昨天", "前天", "昨晚", "一直", "持续", "间歇", "偶尔", "频繁", "反复");

    /**
     * 构建设备信息档案。
     *
     * @param currentMessage 当前用户消息
     * @param priorMessages  会话内历史消息（含 user/assistant，仅取 user 内容）
     */
    public DeviceInformation build(String currentMessage, List<HistoryMessage> priorMessages) {
        DeviceInformation info = new DeviceInformation();
        if (priorMessages != null) {
            for (HistoryMessage message : priorMessages) {
                if (ChatHistoryService.ROLE_USER.equals(message.getRole())) {
                    absorb(info, message.getContent());
                }
            }
        }
        absorb(info, currentMessage);
        return info;
    }

    /** 单条消息吸收到档案。 */
    private void absorb(DeviceInformation info, String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        String text = raw.trim();

        if (!StringUtils.hasText(info.getDeviceModel())) {
            String model = firstGroup(EXPLICIT_MODEL, text);
            if (model == null) {
                Matcher standalone = STANDALONE_MODEL.matcher(text);
                if (standalone.find()) {
                    model = standalone.group().replace(" ", "").replace("_", "-");
                }
            }
            if (model != null) {
                info.setDeviceModel(model);
            }
        }

        if (!StringUtils.hasText(info.getFaultCode())) {
            String code = firstGroup(EXPLICIT_FAULT_CODE, text);
            if (code == null) {
                Matcher standalone = STANDALONE_FAULT_CODE.matcher(text);
                if (standalone.find()) {
                    code = standalone.group(1) + standalone.group(2);
                }
            }
            if (code != null) {
                info.setFaultCode(code);
            }
        }

        if (!StringUtils.hasText(info.getDeviceSerial())) {
            String serial = firstGroup(SERIAL, text);
            if (serial != null) {
                info.setDeviceSerial(serial);
            }
        }

        for (Map.Entry<Pattern, String> entry : SYMPTOM_RULES.entrySet()) {
            if (entry.getKey().matcher(text).find()) {
                info.addSymptom(entry.getValue());
            }
        }

        mergeOccurrence(info, text);
    }

    /** 合并时间/频率/时长描述。 */
    private void mergeOccurrence(DeviceInformation info, String text) {
        StringBuilder builder = new StringBuilder();
        for (String word : TIME_WORDS) {
            if (text.contains(word)) {
                builder.append(word).append(' ');
            }
        }
        Matcher duration = DURATION.matcher(text);
        if (duration.find()) {
            builder.append(duration.group().replace(" ", "")).append(' ');
        }
        if (builder.length() > 0) {
            String found = builder.toString().trim();
            String existing = info.getOccurrenceInfo();
            info.setOccurrenceInfo(StringUtils.hasText(existing) ? existing + "；" + found : found);
        }
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
