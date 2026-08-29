package dev.reasonweave.domainpack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.reasonweave.shared.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DomainPackCatalog {
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private static final Set<String> QUERY_PLACEHOLDERS = Set.of(
        "title", "description", "event_type", "subject_label", "predicates", "predicate_labels"
    );
    private static final Set<String> RULE_FIELDS = Set.of(
        "id", "version", "hypothesis", "predicate", "when", "relation",
        "expected_weight", "required"
    );
    private static final Set<String> RULE_RELATIONS = Set.of(
        "STRONGLY_SUPPORTS", "SUPPORTS", "PARTIALLY_SUPPORTS", "NEUTRAL",
        "INSUFFICIENT", "PARTIALLY_CONTRADICTS", "CONTRADICTS",
        "STRONGLY_CONTRADICTS"
    );
    private final Path root;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public DomainPackCatalog(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public void validate() {
        JsonNode manifest = manifest();
        requireText(manifest, "key", "manifest.key");
        requireText(manifest, "version", "manifest.version");
        requireText(manifest, "compatible_eventir", "manifest.compatible_eventir");
        requireNonEmptyArray(manifest.path("event_types"), "manifest.event_types");
        Set<String> supportedEventTypes = new HashSet<>();
        for (JsonNode eventType : manifest.path("event_types")) {
            if (!eventType.isTextual() || eventType.asText().isBlank()
                || !supportedEventTypes.add(eventType.asText())) {
                fail("manifest.event_types must contain unique non-blank strings");
            }
        }

        Set<String> vocabularyPredicates = fieldNames(vocabulary().path("predicates"));
        if (vocabularyPredicates.isEmpty()) {
            fail("vocabulary.predicates must not be empty");
        }

        validateEventDefinitions(manifest, supportedEventTypes, vocabularyPredicates);

        Set<String> hypothesisCodes = uniqueTextValues(
            hypotheses().path("hypotheses"), "code", "hypotheses"
        );
        int limit = manifest.path("hypothesis_limit").asInt(4);
        if (hypothesisCodes.isEmpty() || hypothesisCodes.size() > limit) {
            fail("hypotheses must contain between 1 and " + limit + " unique entries");
        }

        Set<String> ruleIds = new HashSet<>();
        Set<String> hypothesesWithRules = new HashSet<>();
        for (JsonNode rule : iterable(rules().path("rules"), "rules.rules")) {
            rule.fieldNames().forEachRemaining(field -> {
                if (!RULE_FIELDS.contains(field)) {
                    fail("unsupported rule field: " + field);
                }
            });
            String id = requireText(rule, "id", "rules[].id");
            if (!ruleIds.add(id)) {
                fail("duplicate rule id: " + id);
            }
            requireText(rule, "version", "rules[].version");
            String hypothesis = requireText(rule, "hypothesis", "rules[].hypothesis");
            if (!hypothesisCodes.contains(hypothesis)) {
                fail("rule references unknown hypothesis: " + hypothesis);
            }
            hypothesesWithRules.add(hypothesis);
            assertPredicate(rule.path("predicate").asText(), vocabularyPredicates, "rule " + id);
            if (!"present".equals(rule.path("when").asText())) {
                fail("rule " + id + " uses unsupported when operator: " + rule.path("when").asText());
            }
            String relation = requireText(rule, "relation", "rules[].relation");
            if (!RULE_RELATIONS.contains(relation)) {
                fail("rule " + id + " uses unsupported relation: " + relation);
            }
            JsonNode weight = rule.path("expected_weight");
            if (!weight.isNumber() || !Double.isFinite(weight.asDouble())
                || weight.asDouble() <= 0 || weight.asDouble() > 1) {
                fail("rule " + id + " expected_weight must be greater than 0 and at most 1");
            }
            if (rule.has("required") && !rule.path("required").isBoolean()) {
                fail("rule " + id + " required must be a boolean");
            }
        }
        if (ruleIds.isEmpty()) {
            fail("rules.rules must not be empty");
        }
        if (!hypothesesWithRules.equals(hypothesisCodes)) {
            Set<String> missing = new HashSet<>(hypothesisCodes);
            missing.removeAll(hypothesesWithRules);
            fail("hypotheses without rules: " + missing);
        }

        Set<String> recommendationIds = new HashSet<>();
        for (JsonNode recommendation : iterable(
            nextEvidence().path("recommendations"), "next-evidence.recommendations"
        )) {
            String id = requireText(recommendation, "id", "recommendations[].id");
            if (!recommendationIds.add(id)) {
                fail("duplicate recommendation id: " + id);
            }
            assertPredicate(
                recommendation.path("expected_predicate").asText(),
                vocabularyPredicates,
                "recommendation " + id
            );
            for (JsonNode hypothesis : iterable(
                recommendation.path("discriminates"), "recommendation.discriminates"
            )) {
                if (!hypothesisCodes.contains(hypothesis.asText())) {
                    fail("recommendation references unknown hypothesis: " + hypothesis.asText());
                }
            }
        }

        JsonNode metadata = knowledgeMetadata();
        Set<String> documentIds = new HashSet<>();
        for (JsonNode descriptor : iterable(metadata.path("documents"), "knowledge.documents")) {
            String id = requireText(descriptor, "id", "knowledge.documents[].id");
            if (!documentIds.add(id)) {
                fail("duplicate knowledge document id: " + id);
            }
            String path = requireText(descriptor, "path", "knowledge.documents[].path");
            safeResolve("knowledge/" + path);
            JsonNode applicableEventTypes = descriptor.path("applicability").path("event_types");
            requireNonEmptyArray(
                applicableEventTypes,
                "knowledge document applicability.event_types"
            );
            for (JsonNode eventType : applicableEventTypes) {
                if (!supportedEventTypes.contains(eventType.asText())) {
                    fail("knowledge document " + id + " references unsupported event type: "
                        + eventType.asText());
                }
            }
            for (JsonNode predicate : iterable(
                descriptor.path("applicability").path("context_predicates"),
                "knowledge document applicability.context_predicates"
            )) {
                assertPredicate(predicate.asText(), vocabularyPredicates, "knowledge document " + id);
            }
            requireNonEmptyArray(
                descriptor.path("expected_predicates"),
                "knowledge document expected_predicates"
            );
            for (JsonNode predicate : descriptor.path("expected_predicates")) {
                assertPredicate(predicate.asText(), vocabularyPredicates, "knowledge document " + id);
            }
        }
        if (documentIds.isEmpty()) {
            fail("knowledge.documents must not be empty");
        }

        Set<String> goldenQueryIds = new HashSet<>();
        for (JsonNode query : iterable(goldenQueries().path("queries"), "golden-queries.queries")) {
            String id = requireText(query, "id", "golden-queries[].id");
            if (!goldenQueryIds.add(id)) {
                fail("duplicate golden query id: " + id);
            }
            JsonNode expectedDocumentIds = query.path("expected_document_ids");
            requireNonEmptyArray(expectedDocumentIds, "golden-queries[].expected_document_ids");
            for (JsonNode expectedDocumentId : expectedDocumentIds) {
                if (!documentIds.contains(expectedDocumentId.asText())) {
                    fail("golden query references unknown knowledge document: "
                        + expectedDocumentId.asText());
                }
            }
        }
        if (goldenQueryIds.isEmpty()) {
            fail("golden queries must not be empty");
        }

        Set<String> goldenInvestigationIds = new HashSet<>();
        for (JsonNode investigation : iterable(
            goldenInvestigations().path("investigations"),
            "golden-investigations.investigations"
        )) {
            String id = requireText(investigation, "id", "golden-investigations[].id");
            if (!goldenInvestigationIds.add(id)) {
                fail("duplicate golden investigation id: " + id);
            }
            JsonNode expectedNode = investigation.path("expected_top_hypothesis");
            String expected = expectedNode.isTextual() && !expectedNode.asText().isBlank()
                ? expectedNode.asText()
                : null;
            if (expected != null) {
                if (!hypothesisCodes.contains(expected)) {
                    fail("golden investigation references unknown hypothesis: " + expected);
                }
            }
            else if (!"EVIDENCE_INSUFFICIENT".equals(
                investigation.path("expected_outcome").asText()
            )) {
                fail("golden investigation requires expected_top_hypothesis or "
                    + "expected_outcome=EVIDENCE_INSUFFICIENT");
            }
            requireNonEmptyArray(
                investigation.path("observations"),
                "golden-investigations[].observations"
            );
            for (JsonNode predicate : investigation.path("observations")) {
                assertPredicate(predicate.asText(), vocabularyPredicates, "golden investigation " + id);
            }
        }
        if (goldenInvestigationIds.isEmpty()) {
            fail("golden investigations must not be empty");
        }

        JsonNode retrieval = retrievalConfig();
        Set<String> allowedRetrievalKeys = Set.of(
            "keyword_top_k", "vector_top_k", "final_top_k", "vector_policy",
            "embedding_query_instruction", "fusion", "weights", "source_diversity",
            "minimum_score", "query_intents"
        );
        retrieval.fieldNames().forEachRemaining(key -> {
            if (!allowedRetrievalKeys.contains(key)) {
                fail("unsupported retrieval config field: " + key);
            }
        });
        positive(retrieval, "keyword_top_k");
        positive(retrieval, "vector_top_k");
        positive(retrieval, "final_top_k");
        positive(retrieval.path("fusion"), "k");
        positive(retrieval.path("source_diversity"), "max_units_per_document");
        if (retrieval.path("weights").path("applicability").asDouble(0) <= 0) {
            fail("retrieval weights.applicability must be positive");
        }
        if (!retrieval.path("minimum_score").isNumber()
            || retrieval.path("minimum_score").asDouble() < 0) {
            fail("retrieval minimum_score must be zero or positive");
        }
        String vectorPolicy = manifest.path("vector_policy").asText("optional");
        if (!vectorPolicy.equals(retrieval.path("vector_policy").asText("optional"))) {
            fail("retrieval vector_policy must match manifest.vector_policy");
        }
        if (!"rrf".equals(retrieval.path("fusion").path("type").asText())) {
            fail("retrieval fusion.type must be rrf");
        }
        if ("required".equals(vectorPolicy)
            && retrieval.path("embedding_query_instruction").asText().isBlank()) {
            fail("vector-required packages must declare embedding_query_instruction");
        }
        validateQueryIntents(retrieval.path("query_intents"));
        validateDeclaredFiles(manifest, metadata);
    }

    private void validateEventDefinitions(
        JsonNode manifest,
        Set<String> supportedEventTypes,
        Set<String> vocabularyPredicates
    ) {
        JsonNode definitions = eventDefinitions().path("event_types");
        if (!definitions.isObject() || definitions.isEmpty()) {
            fail("event-definitions.event_types must be a non-empty object");
        }
        Set<String> definedTypes = fieldNames(definitions);
        if (!definedTypes.equals(supportedEventTypes)) {
            fail("event definitions must exactly match manifest.event_types");
        }
        Set<String> sourceProfiles = fieldNames(manifest.path("source_profiles"));
        definitions.fields().forEachRemaining(entry -> {
            String eventType = entry.getKey();
            JsonNode definition = entry.getValue();
            JsonNode subject = definition.path("subject");
            requireText(subject, "type", "event definition " + eventType + ".subject.type");
            JsonNode identityFields = subject.path("identity_fields");
            requireNonEmptyArray(identityFields, "event definition " + eventType + ".subject.identity_fields");
            JsonNode attributesSchema = subject.path("attributes_schema");
            if (!attributesSchema.isObject()
                || !"object".equals(attributesSchema.path("type").asText())
                || !attributesSchema.path("properties").isObject()) {
                fail("event definition " + eventType + " subject.attributes_schema must describe an object");
            }
            Set<String> identity = new LinkedHashSet<>();
            for (JsonNode field : identityFields) {
                if (!identity.add(field.asText())
                    || !attributesSchema.path("properties").has(field.asText())) {
                    fail("event definition " + eventType + " has an invalid identity field: " + field.asText());
                }
            }
            String labelTemplate = requireText(
                subject,
                "label_template",
                "event definition " + eventType + ".subject.label_template"
            );
            Matcher labelMatcher = TEMPLATE_PLACEHOLDER.matcher(labelTemplate);
            Set<String> labelFields = new LinkedHashSet<>();
            while (labelMatcher.find()) labelFields.add(labelMatcher.group(1));
            if (!identity.containsAll(labelFields) || !labelFields.containsAll(identity)) {
                fail("event definition " + eventType + " label_template must reference every identity field exactly by name");
            }

            JsonNode inputs = definition.path("evidence_inputs");
            if (!inputs.isObject() || inputs.isEmpty()) {
                fail("event definition " + eventType + " must declare evidence_inputs");
            }
            inputs.fields().forEachRemaining(input -> {
                if (!Set.of("observation_bundle", "text", "file", "image").contains(input.getKey())) {
                    fail("event definition " + eventType + " declares unsupported evidence input: " + input.getKey());
                }
                JsonNode value = input.getValue();
                if (!value.path("enabled").isBoolean()) {
                    fail("event definition " + eventType + " evidence input " + input.getKey() + " requires enabled");
                }
            });
            JsonNode text = inputs.path("text");
            if (text.path("enabled").asBoolean(false)) {
                String profile = requireText(text, "source_profile", "text evidence source_profile");
                if (!sourceProfiles.contains(profile)) fail("text evidence references an unknown source profile: " + profile);
                assertPredicate(text.path("predicate").asText(), vocabularyPredicates, "text evidence");
                String verification = text.path("verification_status").asText();
                if (!Set.of("PENDING", "CONFIRMED").contains(verification)) {
                    fail("text evidence verification_status must be PENDING or CONFIRMED");
                }
            }
            JsonNode file = inputs.path("file");
            if (file.path("enabled").asBoolean(false)) {
                String profile = requireText(file, "source_profile", "file evidence source_profile");
                if (!sourceProfiles.contains(profile)) fail("file evidence references an unknown source profile: " + profile);
                requireNonEmptyArray(file.path("content_types"), "file evidence content_types");
            }
            JsonNode image = inputs.path("image");
            if (image.path("enabled").asBoolean(false)) {
                String profile = requireText(image, "source_profile", "image evidence source_profile");
                if (!sourceProfiles.contains(profile)) fail("image evidence references an unknown source profile: " + profile);
                requireNonEmptyArray(image.path("content_types"), "image evidence content_types");
            }
        });
    }

    private static void validateQueryIntents(JsonNode intents) {
        if (!intents.isArray() || intents.isEmpty() || intents.size() > 6) {
            fail("retrieval query_intents must contain between 1 and 6 entries");
        }
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode intent : intents) {
            String type = requireText(intent, "type", "retrieval query_intents[].type");
            if (!types.add(type) || !type.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
                fail("retrieval query intent types must be unique upper snake case values");
            }
            String template = requireText(intent, "template", "retrieval query_intents[].template");
            Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(template);
            while (matcher.find()) {
                if (!QUERY_PLACEHOLDERS.contains(matcher.group(1))) {
                    fail("retrieval query template uses an unsupported placeholder: " + matcher.group(1));
                }
            }
            String remainder = matcher.replaceAll("");
            if (remainder.contains("{") || remainder.contains("}")) {
                fail("retrieval query template contains an invalid placeholder");
            }
        }
    }

    public String fingerprint() {
        try (Stream<Path> paths = Files.walk(root)) {
            String material = paths
                .filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .filter(name -> !"checksums.sha256".equals(name))
                .sorted()
                .map(name -> Hashing.sha256(bytes(name)) + "  " + name)
                .collect(Collectors.joining("\n"));
            return Hashing.sha256(material);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to fingerprint Domain Pack", exception);
        }
    }

    public JsonNode manifest() {
        return yaml("manifest.yaml");
    }

    public JsonNode eventDefinitions() {
        return yaml(manifest().path("event_definitions").asText("event-definitions.yaml"));
    }

    public JsonNode hypotheses() {
        return yaml(manifest().path("hypotheses").asText("hypotheses.yaml"));
    }

    public JsonNode rules() {
        return yaml(manifest().path("rules").asText("rules.yaml"));
    }

    public JsonNode nextEvidence() {
        return yaml(manifest().path("next_evidence").asText("next-evidence.yaml"));
    }

    public JsonNode retrievalConfig() {
        return yaml(manifest().path("knowledge").path("retrieval_config")
            .asText("retrieval-config.yaml"));
    }

    public JsonNode knowledgeMetadata() {
        return yaml(manifest().path("knowledge").path("metadata")
            .asText("knowledge/metadata.yaml"));
    }

    public JsonNode goldenQueries() {
        return yaml(manifest().path("knowledge").path("golden_queries")
            .asText("knowledge/golden-queries.yaml"));
    }

    public JsonNode goldenInvestigations() {
        String path = manifest().path("knowledge").path("golden_investigations").asText();
        return path.isBlank() ? yaml.createObjectNode() : yaml(path);
    }

    public JsonNode vocabulary() {
        return yaml("vocabulary.yaml");
    }

    public JsonNode presentation() {
        Path path = root.resolve("presentation.zh-CN.yaml").normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return yaml.createObjectNode();
        }
        try {
            return yaml.readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Domain Pack presentation metadata", exception);
        }
    }

    public JsonNode yaml(String relativePath) {
        Path path = safeResolve(relativePath);
        try {
            return yaml.readTree(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Domain Pack file: " + relativePath, exception);
        }
    }

    public String text(String relativePath) {
        Path path = safeResolve(relativePath);
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Domain Pack file: " + relativePath, exception);
        }
    }

    private byte[] bytes(String relativePath) {
        try {
            return Files.readAllBytes(safeResolve(relativePath));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Domain Pack file: " + relativePath, exception);
        }
    }

    private void validateDeclaredFiles(JsonNode manifest, JsonNode metadata) {
        Set<String> declared = new LinkedHashSet<>(List.of(
            "manifest.yaml",
            "vocabulary.yaml",
            "presentation.zh-CN.yaml",
            "LICENSES.yaml",
            "NOTICE.md",
            "checksums.sha256",
            manifest.path("event_definitions").asText(),
            manifest.path("hypotheses").asText(),
            manifest.path("rules").asText(),
            manifest.path("next_evidence").asText(),
            manifest.path("knowledge").path("metadata").asText(),
            manifest.path("knowledge").path("retrieval_config").asText(),
            manifest.path("knowledge").path("golden_queries").asText(),
            manifest.path("knowledge").path("golden_investigations").asText()
        ));
        for (JsonNode descriptor : metadata.path("documents")) {
            declared.add("knowledge/" + descriptor.path("path").asText());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            Set<String> actual = paths.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!declared.equals(actual)) {
                Set<String> missing = new LinkedHashSet<>(declared);
                missing.removeAll(actual);
                Set<String> undeclared = new LinkedHashSet<>(actual);
                undeclared.removeAll(declared);
                fail("package file list differs from declarations; missing=" + missing
                    + ", undeclared=" + undeclared);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to validate Domain Pack file declarations", exception);
        }
    }

    private Path safeResolve(String relativePath) {
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Domain Pack path escapes its root");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Domain Pack file does not exist: " + path);
        }
        return path;
    }

    private static Set<String> fieldNames(JsonNode value) {
        Set<String> result = new HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static Set<String> uniqueTextValues(JsonNode values, String field, String label) {
        Set<String> result = new HashSet<>();
        for (JsonNode value : iterable(values, label)) {
            String text = requireText(value, field, label + "[]." + field);
            if (!result.add(text)) {
                fail("duplicate " + label + " value: " + text);
            }
        }
        return result;
    }

    private static Iterable<JsonNode> iterable(JsonNode value, String label) {
        if (!value.isArray()) {
            fail(label + " must be an array");
        }
        return value;
    }

    private static void requireNonEmptyArray(JsonNode value, String label) {
        if (!value.isArray() || value.isEmpty()) {
            fail(label + " must be a non-empty array");
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                fail(label + " must contain non-blank strings");
            }
        }
    }

    private static String requireText(JsonNode value, String field, String label) {
        String text = value.path(field).asText();
        if (text.isBlank()) {
            fail(label + " must be a non-blank string");
        }
        return text;
    }

    private static void positive(JsonNode value, String field) {
        if (!value.path(field).canConvertToInt() || value.path(field).asInt() <= 0) {
            fail("retrieval " + field + " must be a positive integer");
        }
    }

    private static void assertPredicate(String predicate, Set<String> vocabulary, String owner) {
        if (predicate.isBlank() || !vocabulary.contains(predicate)) {
            fail(owner + " references unknown predicate: " + predicate);
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("Invalid Domain Pack: " + message);
    }
}
