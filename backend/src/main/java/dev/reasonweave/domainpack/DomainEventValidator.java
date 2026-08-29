package dev.reasonweave.domainpack;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.reasonweave.event.EventModels;
import dev.reasonweave.shared.ApiException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates domain-specific event subjects without embedding domain semantics in the engine. */
@Component
public class DomainEventValidator {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(
        SpecVersion.VersionFlag.V202012
    );

    public void validateEvent(JsonNode eventIr, DomainPackDefinition definition, String eventType) {
        DomainPackDefinition.EventTypeDefinition eventDefinition =
            definition.requireEventTypeDefinition(eventType);
        validateEventRequirements(eventIr.path("event"), eventDefinition);
        JsonNode subjects = eventIr.path("subjects");
        if (!subjects.isArray() || subjects.size() != 1) {
            throw invalid("当前领域包格式要求 EventIR 只包含一个主调查对象", List.of("subjects"));
        }
        JsonNode subject = subjects.get(0);
        if (!eventDefinition.subjectType().equals(subject.path("type").asText())) {
            throw invalid(
                "调查对象类型与领域包事件定义不一致",
                List.of("expected=" + eventDefinition.subjectType(), "actual=" + subject.path("type").asText())
            );
        }
        JsonNode attributes = subject.path("attributes");
        Set<ValidationMessage> messages = schemas.getSchema(eventDefinition.attributesSchema())
            .validate(attributes);
        if (!messages.isEmpty()) {
            throw invalid(
                "调查对象属性不符合领域包 Schema",
                messages.stream().map(ValidationMessage::getMessage).sorted().limit(20).toList()
            );
        }
        String expectedLabel = renderLabel(eventDefinition.labelTemplate(), attributes);
        if (!expectedLabel.equals(subject.path("label").asText())) {
            throw invalid(
                "调查对象 label 必须由领域包身份字段生成",
                List.of("expected=" + expectedLabel)
            );
        }
    }

    private static void validateEventRequirements(
        JsonNode event,
        DomainPackDefinition.EventTypeDefinition definition
    ) {
        if (!"required".equals(definition.timeRangeRequirement())) return;
        JsonNode occurredAt = event.path("occurred_at");
        String start = occurredAt.path("start").asText("");
        String end = occurredAt.path("end").asText("");
        if (start.isBlank() || end.isBlank()) {
            throw invalid(
                "当前事件类型要求完整的发生时间范围",
                List.of("event.occurred_at.start", "event.occurred_at.end")
            );
        }
        try {
            if (!OffsetDateTime.parse(end).isAfter(OffsetDateTime.parse(start))) {
                throw invalid(
                    "事件结束时间必须晚于开始时间",
                    List.of("event.occurred_at.start", "event.occurred_at.end")
                );
            }
        }
        catch (DateTimeParseException exception) {
            throw invalid(
                "事件时间范围必须使用带时区的 RFC 3339 时间",
                List.of("event.occurred_at.start", "event.occurred_at.end")
            );
        }
    }

    public void validateBundleSubject(
        EventModels.EventDetail event,
        JsonNode bundleSubject,
        DomainPackDefinition definition
    ) {
        DomainPackDefinition.EventTypeDefinition eventDefinition =
            definition.requireEventTypeDefinition(event.eventType());
        JsonNode eventSubject = event.eventIr().path("subjects").path(0);
        List<String> mismatches = new ArrayList<>();
        if (!eventDefinition.subjectType().equals(bundleSubject.path("type").asText())) {
            mismatches.add("subject.type");
        }
        JsonNode eventAttributes = eventSubject.path("attributes");
        JsonNode bundleAttributes = bundleSubject.path("attributes");
        for (String field : eventDefinition.identityFields()) {
            JsonNode expected = eventAttributes.path(field);
            JsonNode actual = bundleAttributes.path(field);
            if (expected.isMissingNode() || actual.isMissingNode() || !expected.equals(actual)) {
                mismatches.add("subject.attributes." + field);
            }
        }
        if (!mismatches.isEmpty()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "EVIDENCE_SUBJECT_MISMATCH",
                "Observation Bundle 的调查对象与目标事件不一致",
                Map.of("fields", List.copyOf(mismatches))
            );
        }
    }

    private static String renderLabel(String template, JsonNode attributes) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            JsonNode value = attributes.path(matcher.group(1));
            String replacement = value.isTextual() ? value.asText() : value.toString();
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static ApiException invalid(String message, List<String> errors) {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "EVENT_DOMAIN_SCHEMA_INVALID",
            message,
            Map.of("errors", errors)
        );
    }
}
