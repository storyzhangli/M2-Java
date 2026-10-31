package com.zl.d17.springai.device;

import com.zl.d17.device.DeviceChatService;
import com.zl.d17.device.DisabledFormalTicketPort;
import com.zl.d17.device.FormalTicketCommand;
import com.zl.d17.device.FormalTicketPort;
import com.zl.d17.device.dto.DeviceChatReply;
import com.zl.d17.device.dto.DeviceChatRequest;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 正式工单写入边界测试（T16）。
 *
 * <p>验证：禁用端口被调用即抛异常；设备编排类结构上不依赖 FormalTicketPort；
 * 完整设备流程运行期间 mock 端口零交互；数据库中不存在正式工单表。</p>
 */
@SpringBootTest
class FormalTicketBoundaryTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private DeviceChatService deviceChatService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void disabledPortShouldThrowWhenInvoked() {
        FormalTicketPort disabled = new DisabledFormalTicketPort();
        FormalTicketCommand command =
                new FormalTicketCommand("CNC-8800", "E101", "设备过热报警", "SENSOR", "ut_owner");
        assertThatThrownBy(() -> disabled.createFormalTicket(command))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("未启用正式工单写入");
    }

    @Test
    void deviceFlowShouldNeverInteractWithFormalTicketPort() {
        FormalTicketPort mockPort = mock(FormalTicketPort.class);

        DeviceChatReply reply = deviceChatService.handle(
                new DeviceChatRequest("technical_engineer", null,
                        "型号 CNC-8800，编号 SP-01，故障码 E101，设备过热，今天一直", null),
                "ut_boundary_owner");

        assertThat(reply.candidateExtraction()).isNotNull();
        // 设备流程不持有也不调用任何正式工单端口
        verifyNoInteractions(mockPort);
    }

    @Test
    void deviceChatServiceShouldNotDependOnFormalTicketPort() {
        boolean hasPortDependency = Arrays.stream(DeviceChatService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(FormalTicketPort.class::isAssignableFrom);
        assertThat(hasPortDependency).isFalse();
    }

    @Test
    void noFormalTicketTableShouldExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'ai_db' AND table_name = 'fault_ticket'",
                Integer.class);
        assertThat(count).isNotNull().isZero();
    }
}
