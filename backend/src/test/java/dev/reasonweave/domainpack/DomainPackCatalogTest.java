package dev.reasonweave.domainpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.reasonweave.shared.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainPackCatalogTest {
    @Test
    void readsBundledVersionProvenanceAndCandidateLimit() throws Exception {
        DomainPackCatalog catalog = new DomainPackCatalog(
            Path.of("../domain-packs/kubernetes-pod-diagnostics/1.0.0")
        );

        assertThat(catalog.manifest().path("version").asText()).isEqualTo("1.0.0");
        assertThat(catalog.manifest().path("fixture_only").asBoolean()).isFalse();
        assertThat(catalog.manifest().path("production_allowed").asBoolean()).isTrue();
        assertThat(catalog.hypotheses().path("hypotheses").size()).isLessThanOrEqualTo(4);
        assertThat(catalog.goldenQueries().path("queries").size()).isEqualTo(5);
        catalog.validate();
        String checksumMaterial = Files.readString(catalog.root().resolve("checksums.sha256")).strip();
        assertThat(catalog.fingerprint()).isEqualTo(Hashing.sha256(checksumMaterial));
    }

    @Test
    void rejectsUnsafeKnowledgePathsAndUnknownPredicates(@TempDir Path temporary) throws Exception {
        Path unsafeRoot = copyPack(temporary.resolve("unsafe"));
        Path metadata = unsafeRoot.resolve("knowledge/metadata.yaml");
        Files.writeString(
            metadata,
            Files.readString(metadata).replace("pod-lifecycle-and-crashes.md", "../../outside.md")
        );
        assertThatThrownBy(() -> new DomainPackCatalog(unsafeRoot).validate())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes its root");

        Path predicateRoot = copyPack(temporary.resolve("predicate"));
        Path rules = predicateRoot.resolve("rules.yaml");
        Files.writeString(
            rules,
            Files.readString(rules).replace(
                "predicate: pod_unschedulable",
                "predicate: unknown_predicate"
            )
        );
        assertThatThrownBy(() -> new DomainPackCatalog(predicateRoot).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown predicate");
    }

    @Test
    void fingerprintsKnowledgeContentAndConfiguration(@TempDir Path temporary) throws Exception {
        Path root = copyPack(temporary.resolve("fingerprint"));
        DomainPackCatalog catalog = new DomainPackCatalog(root);
        String before = catalog.fingerprint();
        Path document = root.resolve("knowledge/pod-lifecycle-and-crashes.md");
        Files.writeString(document, Files.readString(document) + "\nFingerprint test change.\n");
        assertThat(catalog.fingerprint()).hasSize(64).isNotEqualTo(before);
    }

    @Test
    void rejectsUnsupportedRuleSemantics(@TempDir Path temporary) throws Exception {
        Path relationRoot = copyPack(temporary.resolve("relation"));
        Path relationRules = relationRoot.resolve("rules.yaml");
        Files.writeString(
            relationRules,
            Files.readString(relationRules).replace(
                "relation: STRONGLY_SUPPORTS",
                "relation: MAYBE_SUPPORTS"
            )
        );
        assertThatThrownBy(() -> new DomainPackCatalog(relationRoot).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unsupported relation");

        Path weightRoot = copyPack(temporary.resolve("weight"));
        Path weightRules = weightRoot.resolve("rules.yaml");
        Files.writeString(
            weightRules,
            Files.readString(weightRules).replaceFirst("expected_weight: 1.00", "expected_weight: 1.01")
        );
        assertThatThrownBy(() -> new DomainPackCatalog(weightRoot).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expected_weight");
    }

    private static Path copyPack(Path target) throws Exception {
        Path source = Path.of("../domain-packs/kubernetes-pod-diagnostics/1.0.0")
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
