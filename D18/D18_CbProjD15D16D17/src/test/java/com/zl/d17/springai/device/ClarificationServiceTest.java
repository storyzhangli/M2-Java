package com.zl.d17.springai.device;

import com.zl.d17.device.ClarificationService;
import com.zl.d17.device.DeviceInformationExtractor;
import com.zl.d17.device.model.ConfidenceLevel;
import com.zl.d17.device.model.DeviceInformation;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺失信息澄清与不确定性表达测试（T05/T06）。
 *
 * <p>T05 验证缺设备型号时优先澄清型号；T06 验证有型号缺故障码时不编造引用、不输出伪造百分比。
 * 本阶段不接知识库，因此不允许出现任何自造文档/条目编号。</p>
 */
@SpringBootTest
class ClarificationServiceTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private DeviceInformationExtractor extractor;

    @Autowired
    private ClarificationService clarificationService;

    @Test
    void t05_missingDeviceModelShouldAskForModelFirst() {
        DeviceInformation info = extractor.build("设备一直报警，好像停了，今天出现的", List.of());
        ClarificationService.ClarificationResult result = clarificationService.evaluate(info);

        assertThat(result.clarificationRequired()).isTrue();
        assertThat(result.missingInformation()).first().isEqualTo("设备型号");
        assertThat(result.clarificationQuestion()).contains("型号");
        assertThat(result.confidenceLevel()).isEqualTo(ConfidenceLevel.LOW);
        // 已描述的症状应被识别，不会重复追问现象
        assertThat(info.getObservedSymptoms()).contains("报警", "停机/无法启动");
    }

    @Test
    void t06_missingFaultCodeShouldNotFabricateCitationOrPercentage() {
        DeviceInformation info = extractor.build(
                "设备型号 CNC-8800，今天反复过热报警，温度很高", List.of());
        ClarificationService.ClarificationResult result = clarificationService.evaluate(info);

        assertThat(result.clarificationRequired()).isTrue();
        assertThat(result.missingInformation()).contains("故障码");
        assertThat(result.clarificationQuestion()).contains("故障码");

        String note = result.uncertaintyNote();
        // 明确声明无来源，不引用未确认资料
        assertThat(note).contains("未检索到可核验的知识来源");
        // 不允许出现自造文档/条目编号
        assertThat(note).doesNotContain("DOC-", "C0", "参考[", "引用[");
        // 不允许出现伪造的置信度百分比
        assertThat(note).doesNotContain("%").contains("非概率");
    }
}
