package com.zl.d17.device.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 设备信息档案（D18 领域模型，可变）。
 *
 * <p>每轮对话由 {@code DeviceInformationExtractor} 基于历史消息与当前输入重建，
 * 服务本身不持有该状态、不额外落库，从而与会话历史保持单一事实来源。</p>
 *
 * <p>字符串字段为 {@code null} 表示“尚未提供”，空串不视为有效信息。</p>
 */
public class DeviceInformation {

    /** 设备型号。 */
    private String deviceModel;

    /** 设备编号/序列号或产线位置。 */
    private String deviceSerial;

    /** 故障码/报警代码。 */
    private String faultCode;

    /** 故障发生时间、频率或持续状态描述。 */
    private String occurrenceInfo;

    /** 可观察症状集合（去重并保序）。 */
    private final Set<String> observedSymptoms = new LinkedHashSet<>();

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getDeviceSerial() {
        return deviceSerial;
    }

    public void setDeviceSerial(String deviceSerial) {
        this.deviceSerial = deviceSerial;
    }

    public String getFaultCode() {
        return faultCode;
    }

    public void setFaultCode(String faultCode) {
        this.faultCode = faultCode;
    }

    public String getOccurrenceInfo() {
        return occurrenceInfo;
    }

    public void setOccurrenceInfo(String occurrenceInfo) {
        this.occurrenceInfo = occurrenceInfo;
    }

    public Set<String> getObservedSymptoms() {
        return observedSymptoms;
    }

    public void addSymptom(String symptom) {
        if (symptom != null && !symptom.isBlank()) {
            observedSymptoms.add(symptom.trim());
        }
    }
}
