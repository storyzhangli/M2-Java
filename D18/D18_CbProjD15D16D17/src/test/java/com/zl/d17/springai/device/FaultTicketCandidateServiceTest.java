package com.zl.d17.springai.device;

import com.zl.d17.device.ClarificationService;
import com.zl.d17.device.DeviceInformationExtractor;
import com.zl.d17.device.FaultTicketCandidateService;
import com.zl.d17.device.dto.FaultTicketCandidate;
import com.zl.d17.device.model.CandidateStatus;
import com.zl.d17.device.model.DeviceInformation;
import com.zl.d17.device.model.TicketCategory;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障工单候选结构化映射测试（T12/T13）。
 *
 * <p>T12 验证明确症状到工单分类的确定性映射与摘要内容；T13 验证通用/无法判断症状收敛为 UNKNOWN。</p>
 */
@SpringBootTest
class FaultTicketCandidateServiceTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private DeviceInformationExtractor extractor;

    @Autowired
    private ClarificationService clarificationService;

    @Autowired
    private FaultTicketCandidateService candidateService;

    @Test
    void t12_specificSymptomShouldMapToConcreteCategoryAndSummary() {
        DeviceInformation info = extractor.build(
                "型号 CNC-8800，编号 SP-01，故障码 E101，设备过热，今天一直", List.of());
        ClarificationService.ClarificationResult clarification = clarificationService.evaluate(info);

        assertThat(clarification.clarificationRequired()).isFalse();
        FaultTicketCandidate candidate = candidateService.build(info, clarification);

        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.SENSOR);
        assertThat(candidate.deviceModel()).isEqualTo("CNC-8800");
        assertThat(candidate.faultCode()).isEqualTo("E101");
        assertThat(candidate.symptomSummary())
                .contains("CNC-8800").contains("E101").contains("过热/温度异常");
        assertThat(candidate.candidateStatus()).isEqualTo(CandidateStatus.CANDIDATE);
    }

    @Test
    void t13_genericSymptomShouldResolveToUnknownCategory() {
        DeviceInformation info = extractor.build("设备报警了", List.of());
        ClarificationService.ClarificationResult clarification = clarificationService.evaluate(info);
        FaultTicketCandidate candidate = candidateService.build(info, clarification);

        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.UNKNOWN);
        assertThat(candidate.candidateStatus()).isEqualTo(CandidateStatus.NEEDS_CLARIFICATION);
    }
}
