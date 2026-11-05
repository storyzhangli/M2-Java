package com.zl.d23.device;

import com.zl.d23.device.model.CandidateStatus;
import com.zl.d23.device.model.InformationSource;
import com.zl.d23.device.model.TicketCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备域三枚举容错解析回归测试（D23-R04）。
 *
 * <p>纯单元、不启动上下文：验证 {@code @JsonCreator fromValue} 对 null、空白、
 * 大小写与非法取值的收敛口径，保证反序列化不抛异常且保守归类。</p>
 */
class DeviceEnumFallbackTest {

    @Test
    @DisplayName("R04 TicketCategory 合法值精确匹配且大小写不敏感")
    void ticketCategory_validValues() {
        assertThat(TicketCategory.fromValue("electrical")).isEqualTo(TicketCategory.ELECTRICAL);
        assertThat(TicketCategory.fromValue("MECHANICAL")).isEqualTo(TicketCategory.MECHANICAL);
        assertThat(TicketCategory.fromValue(" cooling ")).isEqualTo(TicketCategory.COOLING);
        assertThat(TicketCategory.fromValue("CONTROL")).isEqualTo(TicketCategory.CONTROL);
        assertThat(TicketCategory.fromValue("sensor")).isEqualTo(TicketCategory.SENSOR);
        assertThat(TicketCategory.fromValue("Safety")).isEqualTo(TicketCategory.SAFETY);
    }

    @Test
    @DisplayName("R04 TicketCategory null/空白/非法值统一收敛 UNKNOWN")
    void ticketCategory_fallbacks() {
        assertThat(TicketCategory.fromValue(null)).isEqualTo(TicketCategory.UNKNOWN);
        assertThat(TicketCategory.fromValue("")).isEqualTo(TicketCategory.UNKNOWN);
        assertThat(TicketCategory.fromValue("   ")).isEqualTo(TicketCategory.UNKNOWN);
        assertThat(TicketCategory.fromValue("nonsense")).isEqualTo(TicketCategory.UNKNOWN);
    }

    @Test
    @DisplayName("R04 CandidateStatus 仅 CANDIDATE 命中，其余保守 NEEDS_CLARIFICATION")
    void candidateStatus_fallbacks() {
        assertThat(CandidateStatus.fromValue("candidate")).isEqualTo(CandidateStatus.CANDIDATE);
        assertThat(CandidateStatus.fromValue("CANDIDATE")).isEqualTo(CandidateStatus.CANDIDATE);
        assertThat(CandidateStatus.fromValue(" candidate ")).isEqualTo(CandidateStatus.CANDIDATE);
        assertThat(CandidateStatus.fromValue(null)).isEqualTo(CandidateStatus.NEEDS_CLARIFICATION);
        assertThat(CandidateStatus.fromValue("")).isEqualTo(CandidateStatus.NEEDS_CLARIFICATION);
        assertThat(CandidateStatus.fromValue("finished")).isEqualTo(CandidateStatus.NEEDS_CLARIFICATION);
    }

    @Test
    @DisplayName("R04 InformationSource 合法值匹配，非法值收敛 MODEL_INFERRED")
    void informationSource_unknownFallsBackModelInferred() {
        assertThat(InformationSource.fromValue("user_stated"))
                .isEqualTo(InformationSource.USER_STATED);
        assertThat(InformationSource.fromValue("SESSION_INFERRED"))
                .isEqualTo(InformationSource.SESSION_INFERRED);
        assertThat(InformationSource.fromValue("model_inferred"))
                .isEqualTo(InformationSource.MODEL_INFERRED);
        assertThat(InformationSource.fromValue("hearsay"))
                .isEqualTo(InformationSource.MODEL_INFERRED);
        assertThat(InformationSource.fromValue(""))
                .isEqualTo(InformationSource.MODEL_INFERRED);
    }

    @Test
    @DisplayName("R04 InformationSource null 按用户陈述处理")
    void informationSource_nullReturnsUserStated() {
        assertThat(InformationSource.fromValue(null)).isEqualTo(InformationSource.USER_STATED);
    }
}
