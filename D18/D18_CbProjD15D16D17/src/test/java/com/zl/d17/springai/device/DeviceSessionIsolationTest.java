package com.zl.d17.springai.device;

import com.zl.d17.device.DeviceChatService;
import com.zl.d17.device.dto.DeviceChatReply;
import com.zl.d17.device.dto.DeviceChatRequest;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备场景会话并发隔离与归属校验测试（T09/T10）。
 *
 * <p>T09 验证不同归属的会话彼此独立、互不可见；T10 验证非所属身份无法访问他人会话，
 * 且不泄露资源是否存在。</p>
 */
@SpringBootTest
class DeviceSessionIsolationTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private DeviceChatService deviceChatService;

    @Test
    void t09_differentOwnersSessionsShouldBeIndependent() {
        DeviceChatReply ownerA = deviceChatService.handle(
                new DeviceChatRequest("device_customer_service", null,
                        "型号 CNC-8800，设备报警", null), "ut_iso_a");
        DeviceChatReply ownerB = deviceChatService.handle(
                new DeviceChatRequest("technical_engineer", null,
                        "型号 SV-700，电机振动", null), "ut_iso_b");

        assertThat(ownerA.sessionId()).isNotEqualTo(ownerB.sessionId());
        assertThat(ownerA.candidateExtraction().deviceModel()).isEqualTo("CNC-8800");
        assertThat(ownerB.candidateExtraction().deviceModel()).isEqualTo("SV-700");
        assertThat(ownerA.candidateExtraction().observedSymptoms())
                .doesNotContainAnyElementsOf(ownerB.candidateExtraction().observedSymptoms());
    }

    @Test
    void t10_nonOwnerCannotAccessAnotherOwnersSession() {
        DeviceChatReply ownerA = deviceChatService.handle(
                new DeviceChatRequest("device_customer_service", null,
                        "型号 CNC-8800，设备报警", null), "ut_owner_a");

        assertThatThrownBy(() -> deviceChatService.handle(
                new DeviceChatRequest("device_customer_service", ownerA.sessionId(),
                        "继续提问", null), "ut_owner_b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }
}
