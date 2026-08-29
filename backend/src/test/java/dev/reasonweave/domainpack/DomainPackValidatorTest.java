package dev.reasonweave.domainpack;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainPackValidatorTest {
    @Test
    void rejectsAnEngineRangeThatExcludesTheRunningEngine(@TempDir Path temporary) throws Exception {
        Path root = copyPack("kubernetes-pod-diagnostics", temporary.resolve("incompatible-engine"));
        Path manifest = root.resolve("manifest.yaml");
        Files.writeString(
            manifest,
            Files.readString(manifest).replace(">=0.4.1 <0.5.0", ">=0.5.0 <0.6.0")
        );

        DomainPackValidator validator = new DomainPackValidator(new ObjectMapper());
        assertThatThrownBy(() -> validator.validate(root))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("current engine is 0.4.1");
    }

    @Test
    void acceptsColdHoldingPackAndRejectsUnknownTimeRangeRequirement(@TempDir Path temporary) throws Exception {
        DomainPackValidator validator = new DomainPackValidator(new ObjectMapper());
        Path valid = Path.of("../domain-packs/cold-holding-excursion-diagnostics/1.0.0")
            .toAbsolutePath().normalize();

        assertThat(validator.validate(valid).key()).isEqualTo("cold-holding-excursion-diagnostics");

        Path invalid = copyPack("cold-holding-excursion-diagnostics", temporary.resolve("invalid-time-range"));
        Path eventDefinitions = invalid.resolve("event-definitions.yaml");
        Files.writeString(
            eventDefinitions,
            Files.readString(eventDefinitions).replace("time_range: required", "time_range: inherited")
        );
        assertThatThrownBy(() -> validator.validate(invalid))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("event_requirements.time_range is invalid")
            .hasMessageContaining("cold_holding_temperature_excursion");
    }

    private static Path copyPack(String key, Path target) throws Exception {
        Path source = Path.of("../domain-packs", key, "1.0.0")
            .toAbsolutePath().normalize();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }
}
