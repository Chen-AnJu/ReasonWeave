package dev.reasonweave.domainpack;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Immutable, startup-validated view of one installed Domain Pack version. */
public final class DomainPackDefinition {
    private final Path root;
    private final String key;
    private final String version;
    private final String scopedKey;
    private final boolean fixtureOnly;
    private final boolean productionAllowed;
    private final String vectorPolicy;
    private final Set<String> eventTypes;
    private final Map<String, SourceProfile> sourceProfiles;
    private final String fingerprint;
    private final DomainPackCatalog content;

    DomainPackDefinition(Path root, DomainPackCatalog content) {
        JsonNode manifest = content.manifest();
        this.root = root.toAbsolutePath().normalize();
        this.key = manifest.path("key").asText();
        this.version = manifest.path("version").asText();
        this.scopedKey = key + "/" + version;
        this.fixtureOnly = manifest.path("fixture_only").asBoolean(false);
        this.productionAllowed = manifest.path("production_allowed").asBoolean(false);
        this.vectorPolicy = manifest.path("vector_policy").asText("optional");
        Set<String> configuredEvents = new LinkedHashSet<>();
        manifest.path("event_types").forEach(value -> configuredEvents.add(value.asText()));
        this.eventTypes = Set.copyOf(configuredEvents);
        Map<String, SourceProfile> profiles = new LinkedHashMap<>();
        manifest.path("source_profiles").fields().forEachRemaining(entry -> profiles.put(
            entry.getKey(),
            new SourceProfile(
                entry.getKey(),
                entry.getValue().path("label").asText(entry.getKey()),
                entry.getValue().path("reliability").asDouble()
            )
        ));
        this.sourceProfiles = Map.copyOf(profiles);
        this.fingerprint = content.fingerprint();
        this.content = content;
    }

    public Path root() { return root; }
    public String key() { return key; }
    public String version() { return version; }
    public String scopedKey() { return scopedKey; }
    public boolean fixtureOnly() { return fixtureOnly; }
    public boolean productionAllowed() { return productionAllowed; }
    public String vectorPolicy() { return vectorPolicy; }
    public Set<String> eventTypes() { return eventTypes; }
    public Map<String, SourceProfile> sourceProfiles() { return sourceProfiles; }
    public String fingerprint() { return fingerprint; }
    public DomainPackCatalog content() { return content; }

    public boolean supportsEventType(String eventType) {
        return eventType != null && eventTypes.contains(eventType);
    }

    public SourceProfile requireSourceProfile(String sourceType) {
        SourceProfile profile = sourceProfiles.get(sourceType);
        if (profile == null) {
            throw new IllegalArgumentException("Domain Pack does not declare source type: " + sourceType);
        }
        return profile;
    }

    public JsonNode predicateValueSchema(String predicate) {
        JsonNode definition = content.vocabulary().path("predicates").path(predicate);
        if (definition.isMissingNode()) {
            throw new IllegalArgumentException("Domain Pack does not declare predicate: " + predicate);
        }
        return definition.path("value_schema");
    }

    public EventTypeDefinition requireEventTypeDefinition(String eventType) {
        if (!supportsEventType(eventType)) {
            throw new IllegalArgumentException("Domain Pack does not declare event type: " + eventType);
        }
        JsonNode definition = content.eventDefinitions().path("event_types").path(eventType);
        JsonNode subject = definition.path("subject");
        List<String> identityFields = new java.util.ArrayList<>();
        subject.path("identity_fields").forEach(value -> identityFields.add(value.asText()));
        return new EventTypeDefinition(
            eventType,
            subject.path("type").asText(),
            List.copyOf(identityFields),
            subject.path("label_template").asText(),
            subject.path("attributes_schema").deepCopy(),
            definition.path("evidence_inputs").deepCopy(),
            definition.path("event_requirements").path("time_range").asText("optional")
        );
    }

    public EvidenceInput evidenceInput(String eventType, String inputType) {
        JsonNode value = requireEventTypeDefinition(eventType).evidenceInputs().path(inputType);
        return new EvidenceInput(inputType, value.path("enabled").asBoolean(false), value.deepCopy());
    }

    public record SourceProfile(String type, String label, double reliability) {}

    public record EventTypeDefinition(
        String eventType,
        String subjectType,
        List<String> identityFields,
        String labelTemplate,
        JsonNode attributesSchema,
        JsonNode evidenceInputs,
        String timeRangeRequirement
    ) {}

    public record EvidenceInput(String type, boolean enabled, JsonNode configuration) {}
}
