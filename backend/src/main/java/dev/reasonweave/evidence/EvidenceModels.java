package dev.reasonweave.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.config.ApiOptional;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class EvidenceModels {
    private EvidenceModels() {}

    public record TextEvidenceRequest(
        @NotBlank String text,
        @ApiOptional OffsetDateTime capturedAt,
        @ApiOptional Double latitude,
        @ApiOptional Double longitude
    ) {}

    public record ObservationVerificationRequest(
        @NotBlank String verificationStatus,
        @ApiOptional String description
    ) {}

    public record EvidenceSummary(
        String id,
        String eventId,
        String type,
        String source,
        String status,
        @ApiOptional String originalName,
        @ApiOptional String contentType,
        @ApiOptional String checksumSha256,
        int generation,
        double reliability,
        int observationCount,
        OffsetDateTime createdAt
    ) {}

    public record EvidencePage(
        List<EvidenceSummary> items,
        @ApiOptional String nextCursor,
        int limit,
        long total
    ) {}

    public record ObservationView(
        String id,
        String predicate,
        JsonNode value,
        @ApiOptional String description,
        double modelConfidence,
        String verificationStatus,
        JsonNode provenance,
        int generation,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public record EvidenceDetail(
        EvidenceSummary evidence,
        @ApiOptional String contentText,
        @ApiOptional String blobKey,
        @ApiOptional OffsetDateTime capturedAt,
        @ApiOptional Double latitude,
        @ApiOptional Double longitude,
        JsonNode metadata,
        List<ObservationView> observations
    ) {}

    public record ObservationBundleImportView(
        String schemaVersion,
        String bundleHash,
        boolean duplicate,
        List<EvidenceDetail> evidence
    ) {}

    /**
     * OpenAPI model for observation-bundle/1.0. Runtime validation remains on the
     * original JSON tree so unknown fields cannot be discarded before Schema validation.
     */
    public record ObservationBundleRequest(
        String schemaVersion,
        String domainPack,
        String eventType,
        @ApiOptional String targetVersion,
        ObservationBundleSubject subject,
        List<ObservationBundleEvidenceItem> evidenceItems
    ) {}

    public record ObservationBundleSubject(
        String type,
        String label,
        @ApiOptional Map<String, JsonNode> attributes
    ) {}

    public record ObservationBundleEvidenceItem(
        String externalId,
        String sourceType,
        OffsetDateTime capturedAt,
        List<ObservationBundleObservation> observations
    ) {}

    public record ObservationBundleObservation(
        String predicate,
        JsonNode value,
        double confidence,
        @ApiOptional String description,
        Map<String, JsonNode> sourceLocator
    ) {}
}
