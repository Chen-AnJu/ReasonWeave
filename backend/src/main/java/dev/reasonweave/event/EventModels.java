package dev.reasonweave.event;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.config.ApiOptional;
import dev.reasonweave.investigation.InvestigationModels;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

public final class EventModels {
    private EventModels() {}

    public record CreateEventRequest(
        @NotNull
        @Schema(example = """
            {"schema_version":"eventir/0.1","event":{"type":"equipment_fault","title":"设备温度异常","reference_code":"EVT-DEMO-001","domain_pack":"equipment-fault-test/1.0.0"},"subjects":[{"type":"equipment_asset","label":"pump-001","attributes":{"asset_id":"pump-001"}}]}
            """)
        JsonNode eventIr
    ) {}

    public record EventSummary(
        String id,
        String referenceCode,
        String eventType,
        String title,
        @ApiOptional String description,
        String status,
        String domainPackKey,
        @ApiOptional String locationName,
        @ApiOptional OffsetDateTime occurredStart,
        @ApiOptional OffsetDateTime occurredEnd,
        long version,
        int evidenceCount,
        @ApiOptional Integer latestScore,
        @ApiOptional Double latestCoverage,
        @ApiOptional String topHypothesis,
        OffsetDateTime updatedAt
    ) {}

    public record EventPage(
        List<EventSummary> items,
        @ApiOptional String nextCursor,
        int limit,
        long total
    ) {}

    public record EventDetail(
        String id,
        String referenceCode,
        String eventType,
        String title,
        @ApiOptional String description,
        String status,
        String domainPackKey,
        @ApiOptional String locationName,
        @ApiOptional Double latitude,
        @ApiOptional Double longitude,
        @ApiOptional OffsetDateTime occurredStart,
        @ApiOptional OffsetDateTime occurredEnd,
        long version,
        JsonNode eventIr,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public record EvidenceProjection(
        String id,
        String type,
        String source,
        String status,
        @ApiOptional String originalName,
        @ApiOptional String contentType,
        int generation,
        double reliability,
        OffsetDateTime createdAt
    ) {}

    public record LatestInvestigationView(
        String id,
        int sequenceNo,
        String status,
        long eventVersion,
        int evidenceSnapshotSchemaVersion,
        String evidenceSnapshotHash,
        String knowledgeIndexVersion,
        @ApiOptional InvestigationModels.InvestigationResultView result,
        @ApiOptional OffsetDateTime completedAt
    ) {}

    public record GapSummary(
        String id,
        String title,
        String estimatedImpact,
        String acquisitionCost,
        double priorityScore,
        String status
    ) {}

    public record EventView(
        EventDetail event,
        List<EvidenceProjection> evidence,
        @ApiOptional LatestInvestigationView latestInvestigation,
        boolean stale,
        List<GapSummary> unresolvedGaps
    ) {}
}
