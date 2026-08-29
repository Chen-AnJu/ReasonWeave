package dev.reasonweave.domainpack;

import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.shared.ApiException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DomainPackRegistry {
    private final ReasonWeaveProperties properties;
    private final DomainPackValidator validator;
    private volatile Map<String, DomainPackDefinition> definitions = Map.of();

    public DomainPackRegistry(ReasonWeaveProperties properties, DomainPackValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    @PostConstruct
    public void load() {
        Map<String, DomainPackDefinition> loaded = new LinkedHashMap<>();
        for (Path root : configuredRoots()) {
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("Domain Pack root does not exist: " + root);
            }
            for (Path versionRoot : discover(root)) {
                DomainPackDefinition definition = validator.validate(versionRoot);
                if (!definition.key().equals(versionRoot.getParent().getFileName().toString())
                    || !definition.version().equals(versionRoot.getFileName().toString())) {
                    throw new IllegalStateException(
                        "Domain Pack directory must be <root>/<key>/<version>: " + versionRoot
                    );
                }
                DomainPackDefinition duplicate = loaded.putIfAbsent(definition.scopedKey(), definition);
                if (duplicate != null && !duplicate.fingerprint().equals(definition.fingerprint())) {
                    throw new IllegalStateException(
                        "Conflicting Domain Pack version in configured roots: " + definition.scopedKey()
                    );
                }
            }
        }
        if (loaded.isEmpty()) throw new IllegalStateException("No valid Domain Packs were found");
        definitions = Map.copyOf(loaded);
    }

    public List<DomainPackDefinition> all() {
        return definitions.values().stream()
            .sorted(Comparator.comparing(DomainPackDefinition::scopedKey))
            .toList();
    }

    public DomainPackDefinition require(String scopedKey) {
        DomainPackDefinition definition = definitions.get(scopedKey);
        if (definition == null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DOMAIN_PACK_NOT_INSTALLED",
                "事件引用的领域包未安装",
                Map.of("domain_pack", scopedKey == null ? "" : scopedKey)
            );
        }
        return definition;
    }

    public DomainPackDefinition require(String key, String version) {
        DomainPackDefinition definition = definitions.get(key + "/" + version);
        if (definition == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "领域包版本不存在");
        }
        return definition;
    }

    public DomainPackDefinition requireForEvent(String scopedKey, String eventType) {
        DomainPackDefinition definition = require(scopedKey);
        if (!definition.supportsEventType(eventType)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "EVENT_DOMAIN_MISMATCH",
                "事件类型不属于所选领域包",
                Map.of("event_type", eventType == null ? "" : eventType, "domain_pack", scopedKey)
            );
        }
        return definition;
    }

    private List<Path> configuredRoots() {
        List<Path> result = new ArrayList<>();
        String separators = File.pathSeparatorChar == ':' ? "[,:;]" : "[,;]";
        for (String value : properties.domainPackRoots().split(separators)) {
            if (!value.isBlank()) result.add(Path.of(value.trim()).toAbsolutePath().normalize());
        }
        return List.copyOf(result);
    }

    private static List<Path> discover(Path root) {
        try (Stream<Path> keys = Files.list(root)) {
            List<Path> result = new ArrayList<>();
            for (Path key : keys.filter(Files::isDirectory)
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .sorted().toList()) {
                try (Stream<Path> versions = Files.list(key)) {
                    versions.filter(Files::isDirectory)
                        .filter(path -> Files.isRegularFile(path.resolve("manifest.yaml")))
                        .sorted()
                        .forEach(result::add);
                }
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan Domain Pack root: " + root, exception);
        }
    }
}
