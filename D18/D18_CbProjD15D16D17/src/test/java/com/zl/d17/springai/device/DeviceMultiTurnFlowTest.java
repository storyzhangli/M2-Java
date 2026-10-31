package com.zl.d17.springai.device;

import com.zl.d17.device.DeviceChatService;
import com.zl.d17.device.dto.DeviceChatReply;
import com.zl.d17.device.dto.DeviceChatRequest;
import com.zl.d17.device.model.CandidateStatus;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备场景多轮对话累积测试（T07/T08）。
 *
 * <p>验证跨轮补充型号、编号、故障码与症状后，设备信息正确累积、已提供信息不重复追问，
 * 信息齐备后澄清关闭、候选状态转为 CANDIDATE。全程 mock，不访问外网。</p>
 */
@SpringBootTest
class DeviceMultiTurnFlowTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_multi_owner";

    @Autowired
    private DeviceChatService deviceChatService;

    @Test
    void t07_t08_informationShouldAccumulateAcrossTurnsAndStopReAsking() {
        DeviceChatReply turn1 = deviceChatService.handle(
                new DeviceChatRequest("device_customer_service", null,
                        "设备一直报警停了，今天出现的", null), OWNER);
        assertThat(turn1.clarificationRequired()).isTrue();
        assertThat(turn1.missingInformation()).contains("设备型号");
        String sessionId = turn1.sessionId();

        DeviceChatReply turn2 = deviceChatService.handle(
                new DeviceChatRequest("device_customer_service", sessionId,
                        "型号是 CNC-8800", null), OWNER);
        assertThat(turn2.candidateExtraction().deviceModel()).isEqualTo("CNC-8800");
        assertThat(turn2.missingInformation()).doesNotContain("设备型号");

        DeviceChatReply turn3 = deviceChatService.handle(
                new DeviceChatRequest("device_customer_service", sessionId,
                        "设备编号 SP-09，故障码 E101，还有点过热", null), OWNER);

        assertThat(turn3.clarificationRequired()).isFalse();
        assertThat(turn3.candidateExtraction().faultCode()).isEqualTo("E101");
        assertThat(turn3.candidateExtraction().deviceModel()).isEqualTo("CNC-8800");
        assertThat(turn3.candidateExtraction().observedSymptoms())
                .contains("报警", "停机/无法启动", "过热/温度异常");
        assertThat(turn3.candidateExtraction().candidateStatus()).isEqualTo(CandidateStatus.CANDIDATE);
    }
}
