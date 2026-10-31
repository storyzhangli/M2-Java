package com.zl.d17.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d17.device.dto.FaultTicketCandidate;
import com.zl.d17.device.dto.RawCandidatePayload;
import com.zl.d17.device.model.CandidateStatus;
import com.zl.d17.device.model.InformationSource;
import com.zl.d17.device.model.TicketCategory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结构化候选解析器（D18）。
 *
 * <p>负责把“模型返回的原始文本”解析为候选 DTO，覆盖真实结构化输出的容错边界：</p>
 * <ul>
 *   <li>剥离 Markdown 代码围栏后再解析；</li>
 *   <li>非法 JSON 抛 {@link StructuredParseException}，不吞异常、不伪造候选；</li>
 *   <li>未知枚举经 {@code fromValue} 容错收敛（分类归 UNKNOWN）；</li>
 *   <li>字段缺失/超长由 Bean Validation 明确报错，指出具体字段。</li>
 * </ul>
 */
@Component
public class StructuredCandidateParser {

    private final ObjectMapper objectMapper;

    private final Validator validator;

    public StructuredCandidateParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /** 受控解析异常：仅用于原始文本无法解析为合法 JSON 的场景。 */
    public static class StructuredParseException extends RuntimeException {
        public StructuredParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 解析模型原始文本为候选 DTO。
     *
     * @param raw 模型原始输出（可能含代码围栏或非法 JSON）
     */
    public FaultTicketCandidate parse(String raw) {
        String json = stripFence(raw);
        RawCandidatePayload payload;
        try {
            payload = objectMapper.readValue(json, RawCandidatePayload.class);
        } catch (JsonProcessingException e) {
            throw new StructuredParseException("模型输出不是合法 JSON，无法生成候选", e);
        }
        FaultTicketCandidate candidate = new FaultTicketCandidate(
                payload.symptomSummary(),
                TicketCategory.fromValue(payload.ticketCategory()),
                payload.deviceModel(),
                payload.faultCode(),
                payload.observedSymptoms() == null ? List.of() : payload.observedSymptoms(),
                payload.missingInformation() == null ? List.of() : payload.missingInformation(),
                payload.uncertaintyNote(),
                CandidateStatus.fromValue(payload.candidateStatus()),
                InformationSource.fromValue(payload.sourceType())
        );
        validate(candidate);
        return candidate;
    }

    private String stripFence(String raw) {
        if (raw == null) {
            throw new StructuredParseException("模型输出为空，无法解析", null);
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[A-Za-z]*\\s*", "")
                    .replaceAll("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private void validate(FaultTicketCandidate candidate) {
        Set<ConstraintViolation<FaultTicketCandidate>> violations = validator.validate(candidate);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("模型候选字段校验失败: " + detail);
        }
    }
}
