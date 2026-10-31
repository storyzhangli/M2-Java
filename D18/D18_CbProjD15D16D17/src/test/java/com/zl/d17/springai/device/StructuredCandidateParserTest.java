package com.zl.d17.springai.device;

import com.zl.d17.device.StructuredCandidateParser;
import com.zl.d17.device.dto.FaultTicketCandidate;
import com.zl.d17.device.model.TicketCategory;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化候选解析容错测试（T14/T15）。
 *
 * <p>T14 覆盖非法 JSON、空输入与代码围栏；T15 覆盖字段缺失、超长与未知枚举。
 * 解析失败受控抛异常，不伪造候选；未知枚举收敛为 UNKNOWN。</p>
 */
@SpringBootTest
class StructuredCandidateParserTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private StructuredCandidateParser parser;

    @Test
    void t14_illegalJsonAndEmptyInputShouldThrowParseException() {
        assertThatThrownBy(() -> parser.parse("{这不是合法 JSON"))
                .isInstanceOf(StructuredCandidateParser.StructuredParseException.class);
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(StructuredCandidateParser.StructuredParseException.class);
    }

    @Test
    void t14_fencedJsonShouldBeStrippedAndParsed() {
        String fenced = "```json\n"
                + "{\"symptomSummary\":\"设备过热报警\",\"ticketCategory\":\"SENSOR\","
                + "\"deviceModel\":\"CNC-8800\",\"faultCode\":\"E101\","
                + "\"observedSymptoms\":[\"过热/温度异常\"],\"missingInformation\":[],"
                + "\"uncertaintyNote\":\"无来源\",\"candidateStatus\":\"CANDIDATE\","
                + "\"sourceType\":\"USER_STATED\"}\n```";
        FaultTicketCandidate candidate = parser.parse(fenced);
        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.SENSOR);
        assertThat(candidate.deviceModel()).isEqualTo("CNC-8800");
    }

    @Test
    void t15_unknownEnumShouldResolveToUnknown() {
        String json = "{\"symptomSummary\":\"奇怪现象\",\"ticketCategory\":\"MYSTERY\","
                + "\"candidateStatus\":\"CANDIDATE\",\"sourceType\":\"USER_STATED\"}";
        FaultTicketCandidate candidate = parser.parse(json);
        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.UNKNOWN);
    }

    @Test
    void t15_blankSummaryShouldFailValidation() {
        String json = "{\"symptomSummary\":\"   \",\"ticketCategory\":\"SENSOR\","
                + "\"candidateStatus\":\"CANDIDATE\",\"sourceType\":\"USER_STATED\"}";
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symptomSummary");
    }

    @Test
    void t15_overlengthSummaryShouldFailValidation() {
        String longSummary = "a".repeat(501);
        String json = "{\"symptomSummary\":\"" + longSummary + "\",\"ticketCategory\":\"SENSOR\","
                + "\"candidateStatus\":\"CANDIDATE\",\"sourceType\":\"USER_STATED\"}";
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symptomSummary");
    }
}
