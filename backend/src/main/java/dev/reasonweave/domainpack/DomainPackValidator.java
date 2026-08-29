package dev.reasonweave.domainpack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.reasonweave.shared.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class DomainPackValidator {
    private static final String ENGINE_VERSION = "0.4.1";
    private static final String MANIFEST_SCHEMA = "contracts/domain-pack/manifest-1.schema.json";
    private static final int MAX_FILES = 500;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final Pattern CHECKSUM = Pattern.compile("^([0-9a-f]{64})  ([^\\r\\n]+)$");
    private static final Pattern ENGINE_RANGE = Pattern.compile(
        "^>=([0-9]+\\.[0-9]+\\.[0-9]+) <([0-9]+\\.[0-9]+\\.[0-9]+)$"
    );
    private static final Set<String> ALLOWED_SUFFIXES = Set.of(
        ".yaml", ".yml", ".json", ".md", ".txt", ".sha256"
    );
    private final JsonSchema manifestSchema;

    public DomainPackValidator(ObjectMapper mapper) throws IOException {
        JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        ClassPathResource resource = new ClassPathResource(MANIFEST_SCHEMA);
        try (var input = resource.getInputStream()) {
            this.manifestSchema = schemas.getSchema(mapper.readTree(input));
        }
    }

    public DomainPackDefinition validate(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        validateFilesystem(normalized);
        DomainPackCatalog content = new DomainPackCatalog(normalized);
        content.validate();
        validateFormat(content);
        verifyChecksums(normalized);
        return new DomainPackDefinition(normalized, content);
    }

    private static void validateFilesystem(Path root) {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            fail("Domain Pack root must be a real directory: " + root);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> entries = paths.filter(path -> !path.equals(root)).toList();
            if (entries.size() > MAX_FILES) fail("Domain Pack contains more than 500 entries");
            long total = 0;
            for (Path path : entries) {
                if (Files.isSymbolicLink(path)) fail("Links are not allowed: " + root.relativize(path));
                if (Files.isDirectory(path)) continue;
                if (!Files.isRegularFile(path)) fail("Unsupported package entry: " + root.relativize(path));
                String name = path.getFileName().toString();
                if (ALLOWED_SUFFIXES.stream().noneMatch(name::endsWith)) {
                    fail("File type is not allowed: " + root.relativize(path));
                }
                long size = Files.size(path);
                if (size > MAX_FILE_BYTES) fail("File exceeds 5 MiB: " + root.relativize(path));
                total += size;
            }
            if (total > MAX_TOTAL_BYTES) fail("Domain Pack exceeds 50 MiB unpacked");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect Domain Pack: " + root, exception);
        }
    }

    private void validateFormat(DomainPackCatalog content) {
        JsonNode manifest = content.manifest();
        Set<ValidationMessage> manifestErrors = manifestSchema.validate(manifest);
        if (!manifestErrors.isEmpty()) {
            fail("manifest.yaml does not match Format 1: " + manifestErrors.stream()
                .map(ValidationMessage::getMessage).sorted().limit(20).toList());
        }
        if (!"1.0".equals(manifest.path("format_version").asText())) {
            fail("manifest.format_version must be 1.0");
        }
        if (!"0.1".equals(manifest.path("compatible_eventir").asText())) {
            fail("manifest.compatible_eventir must be 0.1");
        }
        validateEngineCompatibility(manifest.path("engine").asText());
        String vectorPolicy = manifest.path("vector_policy").asText("optional");
        if (!Set.of("required", "optional", "disabled").contains(vectorPolicy)) {
            fail("manifest.vector_policy is invalid");
        }
        JsonNode profiles = manifest.path("source_profiles");
        if (!profiles.isObject() || profiles.isEmpty()) fail("manifest.source_profiles must not be empty");
        profiles.fields().forEachRemaining(entry -> {
            double reliability = entry.getValue().path("reliability").asDouble(-1);
            if (reliability < 0 || reliability > 1) {
                fail("source profile reliability must be between 0 and 1: " + entry.getKey());
            }
        });
        JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        content.eventDefinitions().path("event_types").fields().forEachRemaining(entry -> {
            JsonNode requirements = entry.getValue().path("event_requirements");
            if (!requirements.isMissingNode() && !requirements.isObject()) {
                fail("event_requirements must be an object: " + entry.getKey());
            }
            String timeRange = requirements.path("time_range").asText("optional");
            if (!Set.of("optional", "required").contains(timeRange)) {
                fail("event_requirements.time_range is invalid: " + entry.getKey());
            }
            if (requirements.isObject()) {
                requirements.fieldNames().forEachRemaining(field -> {
                    if (!"time_range".equals(field)) {
                        fail("event_requirements contains an unsupported field: " + field);
                    }
                });
            }
            JsonNode attributesSchema = entry.getValue().path("subject").path("attributes_schema");
            try {
                schemas.getSchema(attributesSchema);
            } catch (RuntimeException exception) {
                fail("subject attributes_schema is invalid: " + entry.getKey());
            }
            JsonNode presentation = content.presentation().path("event_types").path(entry.getKey());
            if (!presentation.isObject()) {
                fail("presentation is missing event type: " + entry.getKey());
            }
            Set<String> properties = new HashSet<>();
            attributesSchema.path("properties").fieldNames().forEachRemaining(properties::add);
            JsonNode fields = presentation.path("fields");
            if (!fields.isObject() || !fields.fieldNames().hasNext()) {
                fail("presentation fields are required for event type: " + entry.getKey());
            }
            fields.fields().forEachRemaining(field -> {
                if (!properties.contains(field.getKey())) {
                    fail("presentation references an unknown subject field: " + field.getKey());
                }
                String control = field.getValue().path("control").asText();
                if (!Set.of("text", "number", "select", "boolean").contains(control)) {
                    fail("presentation uses an unsupported control: " + control);
                }
            });
        });
        content.vocabulary().path("predicates").fields().forEachRemaining(entry -> {
            JsonNode valueSchema = entry.getValue().path("value_schema");
            if (!valueSchema.isObject()) {
                fail("predicate value_schema must be an object: " + entry.getKey());
            }
            try {
                schemas.getSchema(valueSchema);
            } catch (RuntimeException exception) {
                fail("predicate value_schema is invalid: " + entry.getKey());
            }
        });
        if (!Files.isRegularFile(content.root().resolve("LICENSES.yaml"))) {
            fail("LICENSES.yaml is required");
        }
        if (!Files.isRegularFile(content.root().resolve("NOTICE.md"))) {
            fail("NOTICE.md is required");
        }
        JsonNode licenses = content.yaml("LICENSES.yaml").path("components");
        if (!licenses.isArray() || licenses.isEmpty()) fail("LICENSES.yaml components must not be empty");
        Set<String> declaredLicenses = new HashSet<>();
        for (JsonNode component : licenses) {
            for (String field : List.of("scope", "license", "source", "revision")) {
                if (component.path(field).asText().isBlank()) {
                    fail("LICENSES.yaml component is missing " + field);
                }
            }
            JsonNode derivedHashes = component.path("derived_content_sha256");
            if (!derivedHashes.isMissingNode() && !derivedHashes.isObject()) {
                fail("LICENSES.yaml derived_content_sha256 must be an object");
            }
            derivedHashes.fields().forEachRemaining(entry -> {
                String relative = entry.getKey();
                String expected = entry.getValue().asText();
                Path path = content.root().resolve(relative).normalize();
                if (!path.startsWith(content.root()) || !Files.isRegularFile(path)) {
                    fail("LICENSES.yaml derived content path is invalid: " + relative);
                }
                if (!expected.matches("[0-9a-f]{64}")) {
                    fail("LICENSES.yaml derived content hash is invalid: " + relative);
                }
                try {
                    if (!expected.equals(Hashing.sha256(Files.readAllBytes(path)))) {
                        fail("LICENSES.yaml derived content hash mismatch: " + relative);
                    }
                }
                catch (IOException exception) {
                    throw new IllegalStateException(
                        "Unable to verify LICENSES.yaml derived content: " + relative,
                        exception
                    );
                }
            });
            declaredLicenses.add(component.path("license").asText());
        }
        for (JsonNode document : content.knowledgeMetadata().path("documents")) {
            for (String field : List.of("source_url", "source_revision", "source_license")) {
                if (document.path(field).asText().isBlank()) {
                    fail("knowledge document " + document.path("id").asText() + " is missing " + field);
                }
            }
            if (!declaredLicenses.contains(document.path("source_license").asText())) {
                fail("knowledge document uses an undeclared license: " + document.path("id").asText());
            }
        }
    }

    private static void validateEngineCompatibility(String range) {
        Matcher matcher = ENGINE_RANGE.matcher(range);
        if (!matcher.matches()) {
            fail("manifest.engine must use the supported form >=x.y.z <x.y.z");
        }
        if (compareSemver(ENGINE_VERSION, matcher.group(1)) < 0
            || compareSemver(ENGINE_VERSION, matcher.group(2)) >= 0) {
            fail("Domain Pack requires engine " + range + ", current engine is " + ENGINE_VERSION);
        }
    }

    private static int compareSemver(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < 3; index++) {
            int comparison = Integer.compare(
                Integer.parseInt(leftParts[index]),
                Integer.parseInt(rightParts[index])
            );
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static void verifyChecksums(Path root) {
        Path checksumFile = root.resolve("checksums.sha256");
        if (!Files.isRegularFile(checksumFile)) fail("checksums.sha256 is required");
        try {
            Map<String, String> expected = new HashMap<>();
            for (String line : Files.readAllLines(checksumFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Matcher matcher = CHECKSUM.matcher(line);
                if (!matcher.matches() || expected.put(matcher.group(2), matcher.group(1)) != null) {
                    fail("checksums.sha256 contains an invalid or duplicate entry");
                }
            }
            Set<String> actualNames = new HashSet<>();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = root.relativize(path).toString().replace('\\', '/');
                    if ("checksums.sha256".equals(name)) continue;
                    actualNames.add(name);
                    String declared = expected.get(name);
                    String actual = Hashing.sha256(Files.readAllBytes(path));
                    if (!actual.equals(declared)) fail("checksum mismatch: " + name);
                }
            }
            if (!expected.keySet().equals(actualNames)) fail("checksums.sha256 file list does not match package content");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to verify Domain Pack checksums", exception);
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("Invalid Domain Pack: " + message);
    }
}
