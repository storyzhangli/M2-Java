package com.zl.d23.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d23.device.dto.FaultTicketCandidate;
import com.zl.d23.device.model.CandidateStatus;
import com.zl.d23.device.model.InformationSource;
import com.zl.d23.device.model.TicketCategory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化候选解析器回归测试（D23-R04）。
 *
 * <p>不启动 Spring 上下文、不触网：通过包内测试构造器注入真实 ObjectMapper 与标准
 * Jakarta Bean Validation 校验器，覆盖代码围栏剥离、宽松映射、枚举容错、非法 JSON
 * 与字段级校验违例，断言最终 DTO 的真实内容而非仅“不抛异常”。</p>
 */
class StructuredCandidateParserTest {

    private static StructuredCandidateParser parser;

    @BeforeAll
    static void init() {
        ObjectMapper mapper = new ObjectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        parser = new StructuredCandidateParser(mapper, validator);
    }

    @Test
    @DisplayName("R04 纯 JSON 正确映射全部字段")
    void parse_validPlainJson_mapsAllFields() {
        String json = """
                {
                  "symptomSummary": "设备无法开机，按启动键无反应",
                  "ticketCategory": "ELECTRICAL",
                  "deviceModel": "CNC-850",
                  "faultCode": "E101",
                  "observedSymptoms": ["无法启动", "无指示灯"],
                  "missingInformation": ["设备序列号"],
                  "uncertaintyNote": "信息基本齐全",
                  "candidateStatus": "CANDIDATE",
                  "sourceType": "USER_STATED"
                }
                """;

        FaultTicketCandidate candidate = parser.parse(json);

        assertThat(candidate.symptomSummary()).isEqualTo("设备无法开机，按启动键无反应");
        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.ELECTRICAL);
        assertThat(candidate.deviceModel()).isEqualTo("CNC-850");
        assertThat(candidate.faultCode()).isEqualTo("E101");
        assertThat(candidate.observedSymptoms()).containsExactly("无法启动", "无指示灯");
        assertThat(candidate.missingInformation()).containsExactly("设备序列号");
        assertThat(candidate.uncertaintyNote()).isEqualTo("信息基本齐全");
        assertThat(candidate.candidateStatus()).isEqualTo(CandidateStatus.CANDIDATE);
        assertThat(candidate.sourceType()).isEqualTo(InformationSource.USER_STATED);
    }

    @Test
    @DisplayName("R04 带语言标记的代码围栏被剥离后正常解析")
    void parse_fencedJson_stripsFence() {
        String fenced = """
                ```json
                {
                  "symptomSummary": "主轴过热报警",
                  "ticketCategory": "COOLING",
                  "candidateStatus": "NEEDS_CLARIFICATION",
                  "sourceType": "SESSION_INFERRED"
                }
                ```
                """;

        FaultTicketCandidate candidate = parser.parse(fenced);

        assertThat(candidate.symptomSummary()).isEqualTo("主轴过热报警");
        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.COOLING);
        assertThat(candidate.candidateStatus()).isEqualTo(CandidateStatus.NEEDS_CLARIFICATION);
        assertThat(candidate.sourceType()).isEqualTo(InformationSource.SESSION_INFERRED);
    }

    @Test
    @DisplayName("R04 无语言标记的围栏同样可剥离")
    void parse_fenceWithoutLanguage_stripsFence() {
        String fenced = "```\n"
                + "{\"symptomSummary\":\"振动异常\",\"ticketCategory\":\"MECHANICAL\"}\n"
                + "```";

        FaultTicketCandidate candidate = parser.parse(fenced);

        assertThat(candidate.symptomSummary()).isEqualTo("振动异常");
        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.MECHANICAL);
    }

    @Test
    @DisplayName("R04 模型多输出的未知字段被忽略")
    void parse_extraFields_areIgnored() {
        String json = """
                {
                  "symptomSummary": "安全联锁触发",
                  "ticketCategory": "SAFETY",
                  "unexpectedField": "should-be-ignored",
                  "anotherExtra": 123
                }
                """;

        FaultTicketCandidate candidate = parser.parse(json);

        assertThat(candidate.symptomSummary()).isEqualTo("安全联锁触发");
        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.SAFETY);
    }

    @Test
    @DisplayName("R04 未知分类容错为 UNKNOWN")
    void parse_unknownCategory_fallsBackToUnknown() {
        String json = "{\"symptomSummary\":\"x\",\"ticketCategory\":\"QUANTUM\"}";

        assertThat(parser.parse(json).ticketCategory()).isEqualTo(TicketCategory.UNKNOWN);
    }

    @Test
    @DisplayName("R04 未知候选状态容错为 NEEDS_CLARIFICATION")
    void parse_unknownStatus_fallsBackToNeedsClarification() {
        String json = "{\"symptomSummary\":\"x\",\"candidateStatus\":\"MAYBE\"}";

        assertThat(parser.parse(json).candidateStatus())
                .isEqualTo(CandidateStatus.NEEDS_CLARIFICATION);
    }

    @Test
    @DisplayName("R04 未知来源容错为 MODEL_INFERRED")
    void parse_unknownSource_fallsBackToModelInferred() {
        String json = "{\"symptomSummary\":\"x\",\"sourceType\":\"RUMOR\"}";

        assertThat(parser.parse(json).sourceType()).isEqualTo(InformationSource.MODEL_INFERRED);
    }

    @Test
    @DisplayName("R04 来源为 null 时按用户陈述处理")
    void parse_nullSourceString_returnsUserStated() {
        String json = "{\"symptomSummary\":\"x\",\"sourceType\":null}";

        assertThat(parser.parse(json).sourceType()).isEqualTo(InformationSource.USER_STATED);
    }

    @Test
    @DisplayName("R04 非法 JSON 抛内部 StructuredParseException 而非吞异常")
    void parse_invalidJson_throwsStructuredParseException() {
        String broken = "{\"symptomSummary\": \"缺失右括号\"";

        assertThatThrownBy(() -> parser.parse(broken))
                .isInstanceOf(StructuredCandidateParser.StructuredParseException.class)
                .hasMessageContaining("模型输出不是合法 JSON")
                .cause()
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    @DisplayName("R04 空白输入抛 StructuredParseException")
    void parse_blankInput_throwsStructuredParseException() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(StructuredCandidateParser.StructuredParseException.class);
    }

    @Test
    @DisplayName("R04 故障摘要空白触发 Bean Validation 并指出字段")
    void parse_blankSymptomSummary_throwsIllegalArgument() {
        String json = "{\"symptomSummary\":\"   \"}";

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型候选字段校验失败")
                .hasMessageContaining("symptomSummary");
    }

    @Test
    @DisplayName("R04 故障摘要超长触发校验并指出字段")
    void parse_symptomSummaryTooLong_throwsIllegalArgument() {
        String tooLong = "y".repeat(501);
        String json = "{\"symptomSummary\":\"" + tooLong + "\"}";

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symptomSummary");
    }

    @Test
    @DisplayName("R04 设备型号超长触发校验并指出字段")
    void parse_deviceModelTooLong_throwsIllegalArgument() {
        String tooLong = "m".repeat(129);
        String json = "{\"symptomSummary\":\"ok\",\"deviceModel\":\"" + tooLong + "\"}";

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceModel");
    }

    @Test
    @DisplayName("R04 列表字段缺失时容忍为空列表不报错")
    void parse_nullLists_areTolerated() {
        String json = "{\"symptomSummary\":\"设备异响\"}";

        FaultTicketCandidate candidate = parser.parse(json);

        assertThat(candidate.observedSymptoms()).isEmpty();
        assertThat(candidate.missingInformation()).isEmpty();
        assertThat(candidate.symptomSummary()).isEqualTo("设备异响");
    }

    @Test
    @DisplayName("R04 小写枚举值大小写不敏感映射")
    void parse_lowercaseEnums_mapCaseInsensitive() {
        String json = "{\"symptomSummary\":\"x\",\"ticketCategory\":\"sensor\","
                + "\"candidateStatus\":\"candidate\",\"sourceType\":\"user_stated\"}";

        FaultTicketCandidate candidate = parser.parse(json);

        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.SENSOR);
        assertThat(candidate.candidateStatus()).isEqualTo(CandidateStatus.CANDIDATE);
        assertThat(candidate.sourceType()).isEqualTo(InformationSource.USER_STATED);
    }

    @Test
    @DisplayName("R04 不确定性说明超长触发校验")
    void parse_uncertaintyNoteTooLong_throwsIllegalArgument() {
        String tooLong = "n".repeat(1001);
        String json = "{\"symptomSummary\":\"ok\",\"uncertaintyNote\":\"" + tooLong + "\"}";

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncertaintyNote");
    }

    @Test
    @DisplayName("R04 故障码超长触发校验")
    void parse_faultCodeTooLong_throwsIllegalArgument() {
        String tooLong = "f".repeat(65);
        String json = "{\"symptomSummary\":\"ok\",\"faultCode\":\"" + tooLong + "\"}";

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("faultCode");
    }

    @Test
    @DisplayName("R04 空数组列表正确映射为空列表")
    void parse_emptyLists_mapToEmpty() {
        String json = "{\"symptomSummary\":\"x\",\"observedSymptoms\":[],"
                + "\"missingInformation\":[]}";

        FaultTicketCandidate candidate = parser.parse(json);

        assertThat(candidate.observedSymptoms()).isEqualTo(List.of());
        assertThat(candidate.missingInformation()).isEqualTo(List.of());
    }
}
