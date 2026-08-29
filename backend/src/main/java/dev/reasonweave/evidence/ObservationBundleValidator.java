package dev.reasonweave.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.reasonweave.domainpack.DomainPackDefinition;
import dev.reasonweave.domainpack.DomainEventValidator;
import dev.reasonweave.domainpack.DomainPackRegistry;
import dev.reasonweave.event.EventModels;
import dev.reasonweave.shared.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ObservationBundleValidator {
    public static final String SCHEMA_VERSION = "observation-bundle/1.0";
    private static final String RESOURCE = "contracts/observation-bundle/observation-bundle-1.schema.json";
    private static final Pattern SEMVER = Pattern.compile(
        "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$"
    );
    private final JsonSchema schema;
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(
        SpecVersion.VersionFlag.V202012
    );
    private final DomainPackRegistry domainPacks;
    private final DomainEventValidator domainEventValidator;

    public ObservationBundleValidator(
        ObjectMapper mapper,
        DomainPackRegistry domainPacks,
        DomainEventValidator domainEventValidator
    )
        throws IOException {
        this.domainPacks = domainPacks;
        this.domainEventValidator = domainEventValidator;
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            this.schema = schemas.getSchema(mapper.readTree(input));
        }
    }

    public ValidatedBundle validate(EventModels.EventDetail event, JsonNode bundle) {
        Set<ValidationMessage> messages = schema.validate(bundle);
        if (!messages.isEmpty()) {
            throw invalid(
                "Observation Bundle 不符合 1.0 Schema",
                messages.stream().map(ValidationMessage::getMessage).sorted().limit(20).toList()
            );
        }
        String scopedKey = bundle.path("domain_pack").asText();
        String eventType = bundle.path("event_type").asText();
        if (!event.domainPackKey().equals(scopedKey) || !event.eventType().equals(eventType)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "EVENT_DOMAIN_MISMATCH",
                "Observation Bundle 与目标事件的领域包或事件类型不一致",
                Map.of(
                    "event_domain_pack", event.domainPackKey(),
                    "bundle_domain_pack", scopedKey,
                    "event_type", event.eventType(),
                    "bundle_event_type", eventType
                )
            );
        }
        DomainPackDefinition definition = domainPacks.requireForEvent(scopedKey, eventType);
        if (!definition.evidenceInput(eventType, "observation_bundle").enabled()) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "DOMAIN_EVIDENCE_TYPE_UNSUPPORTED",
                "当前领域包未启用 Observation Bundle 证据入口"
            );
        }
        domainEventValidator.validateBundleSubject(event, bundle.path("subject"), definition);
        validateTargetVersion(definition, bundle.path("target_version").asText());
        Set<String> externalIds = new HashSet<>();
        List<ValidatedItem> items = new ArrayList<>();
        for (JsonNode item : bundle.path("evidence_items")) {
            String externalId = item.path("external_id").asText();
            if (!externalIds.add(externalId)) {
                throw invalid("Observation Bundle 包含重复 external_id", List.of(externalId));
            }
            String sourceType = item.path("source_type").asText();
            DomainPackDefinition.SourceProfile profile;
            try {
                profile = definition.requireSourceProfile(sourceType);
            } catch (IllegalArgumentException exception) {
                throw invalid("Observation Bundle 使用了领域包未声明的 source_type", List.of(sourceType));
            }
            for (JsonNode observation : item.path("observations")) {
                String predicate = observation.path("predicate").asText();
                JsonNode valueSchema;
                try {
                    valueSchema = definition.predicateValueSchema(predicate);
                } catch (IllegalArgumentException exception) {
                    throw invalid("Observation Bundle 使用了领域包未声明的 Predicate", List.of(predicate));
                }
                Set<ValidationMessage> valueMessages = schemas.getSchema(valueSchema)
                    .validate(observation.path("value"));
                if (!valueMessages.isEmpty()) {
                    throw invalid(
                        "Observation 值不符合 Predicate Schema: " + predicate,
                        valueMessages.stream().map(ValidationMessage::getMessage).sorted().toList()
                    );
                }
            }
            items.add(new ValidatedItem(item, profile));
        }
        return new ValidatedBundle(definition, bundle.deepCopy(), List.copyOf(items));
    }

    private static void validateTargetVersion(DomainPackDefinition definition, String value) {
        JsonNode range = definition.content().manifest().path("supported_target_versions");
        if (range.isMissingNode() || range.isEmpty()) return;
        if (value == null || value.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "TARGET_VERSION_REQUIRED",
                "该领域包要求 Observation Bundle 提供目标系统版本"
            );
        }
        int[] actual = semver(value);
        int[] minimum = semver(range.path("minimum").asText());
        int[] maximum = semver(range.path("maximum_exclusive").asText());
        if (compare(actual, minimum) < 0 || compare(actual, maximum) >= 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "TARGET_VERSION_UNSUPPORTED",
                "目标系统版本超出领域包支持范围",
                Map.of(
                    "target_version", value,
                    "minimum", range.path("minimum").asText(),
                    "maximum_exclusive", range.path("maximum_exclusive").asText()
                )
            );
        }
    }

    private static int[] semver(String value) {
        Matcher matcher = SEMVER.matcher(value == null ? "" : value.trim());
        if (!matcher.matches()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "TARGET_VERSION_INVALID",
                "目标系统版本必须使用 SemVer"
            );
        }
        return new int[] {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))
        };
    }

    private static int compare(int[] left, int[] right) {
        for (int index = 0; index < 3; index++) {
            int result = Integer.compare(left[index], right[index]);
            if (result != 0) return result;
        }
        return 0;
    }

    private static ApiException invalid(String message, List<String> errors) {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "OBSERVATION_BUNDLE_INVALID",
            message,
            Map.of("errors", errors)
        );
    }

    public record ValidatedBundle(
        DomainPackDefinition domainPack,
        JsonNode bundle,
        List<ValidatedItem> items
    ) {}

    public record ValidatedItem(JsonNode value, DomainPackDefinition.SourceProfile sourceProfile) {}
}
