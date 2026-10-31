package com.zl.d17.springai.device;

import com.zl.d17.device.DeviceChatService;
import com.zl.d17.device.dto.DeviceChatReply;
import com.zl.d17.device.dto.DeviceChatRequest;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备场景上下文窗口裁剪测试（T11）。
 *
 * <p>把窗口固定为 4 条，多轮后模型只看到最近 4 条 COMPLETED 历史，
 * 模拟回复中回显的“历史消息条数”用于确定性验证裁剪结果。</p>
 */
@SpringBootTest(properties = "app.ai.history.context-window-messages=4")
class DeviceContextWindowTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_device_window";

    @Autowired
    private DeviceChatService deviceChatService;

    @Test
    void t11_deviceFlowShouldOnlySendLatestWindowToModel() {
        DeviceChatReply first = deviceChatService.handle(
                new DeviceChatRequest("technical_engineer", null,
                        "型号 CNC-8800，编号 SP-01，故障码 E101，设备报警，今天一直", null), OWNER);
        String sessionId = first.sessionId();

        deviceChatService.handle(
                new DeviceChatRequest("technical_engineer", sessionId,
                        "型号 CNC-8800，编号 SP-01，故障码 E102，设备过热，今天间歇", null), OWNER);
        DeviceChatReply third = deviceChatService.handle(
                new DeviceChatRequest("technical_engineer", sessionId,
                        "型号 CNC-8800，编号 SP-01，故障码 E103，设备振动，今天频繁", null), OWNER);

        // 三轮共 6 条历史，窗口 4 条，第三轮模型仅看到最近 4 条
        assertThat(third.answer()).contains("历史消息条数=4");
        assertThat(third.clarificationRequired()).isFalse();
    }
}
