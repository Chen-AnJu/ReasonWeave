package dev.reasonweave.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.config.ApiOptional;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;

public final class KnowledgeModels {
    private KnowledgeModels() {}

    public record RetrievalRequest(
        @NotBlank String query,
        @NotBlank String eventType,
        @ApiOptional List<String> observedPredicates,
        @ApiOptional String intent,
        @NotBlank String domainPackKey
    ) {}

    public record SourceView(
        String id,
        String domainPackKey,
        String name,
        String sourceType,
        String version,
        @ApiOptional String license,
        String status,
        boolean fixtureOnly,
        boolean productionAllowed,
        int documentCount,
        int unitCount,
        @ApiOptional String embeddingProvider,
        @ApiOptional String embeddingModel,
        @ApiOptional Integer embeddingDimension,
        @ApiOptional String embeddingModelDigest,
        @ApiOptional String embeddingQueryInstruction,
        @ApiOptional String indexProfileFingerprint,
        @ApiOptional OffsetDateTime publishedAt,
        OffsetDateTime createdAt
    ) {}

    public record DocumentView(
        String id,
        String knowledgeSourceId,
        @ApiOptional String externalId,
        String title,
        String contentType,
        String checksumSha256,
        String language,
        String parseStatus,
        JsonNode metadata,
        int unitCount,
        OffsetDateTime createdAt
    ) {}

    public record SourceDetailView(
        SourceView source,
        List<DocumentView> documents,
        int publishedUnitCount,
        int embeddingUnitCount,
        int citationCount,
        int retrievalUsageCount,
        String currentIndexVersion,
        @ApiOptional EmbeddingProvenanceView embeddingProvenance
    ) {}

    public record UnitSummaryView(
        String id,
        String knowledgeSourceId,
        String documentId,
        @ApiOptional String topic,
        String title,
        JsonNode expectedPredicates,
        JsonNode sourceLocator,
        String sourceVersion,
        String contentHash,
        String status,
        boolean embeddingPresent,
        OffsetDateTime createdAt
    ) {}

    public record UnitPageView(
        List<UnitSummaryView> items,
        @ApiOptional String nextCursor,
        int limit
    ) {}

    public record CitationUsageView(
        String citationId,
        String investigationRunId,
        String eventId,
        String targetType,
        String targetId,
        @ApiOptional String targetCode,
        @ApiOptional String targetTitle,
        JsonNode sourceLocator,
        String sourceVersion,
        String contentHash,
        String usageReason,
        OffsetDateTime createdAt
    ) {}

    public record CitationUsagePageView(
        List<CitationUsageView> items,
        @ApiOptional String nextCursor,
        int limit,
        long total
    ) {}

    public record RetrievalUsageView(
        String retrievalRunId,
        @ApiOptional String investigationRunId,
        String queryIntent,
        @ApiOptional Integer keywordRank,
        @ApiOptional Integer vectorRank,
        int fusionRank,
        double fusionScore,
        boolean selected,
        String selectionReason,
        String indexVersion,
        String embeddingModel,
        OffsetDateTime createdAt
    ) {}

    public record RetrievalUsagePageView(
        List<RetrievalUsageView> items,
        @ApiOptional String nextCursor,
        int limit,
        long total
    ) {}

    public record UnitDetailView(
        String id,
        SourceView source,
        DocumentView document,
        String domainPackKey,
        @ApiOptional String topic,
        String title,
        String content,
        JsonNode applicability,
        JsonNode expectedPredicates,
        JsonNode sourceLocator,
        String sourceVersion,
        String contentHash,
        String status,
        boolean embeddingPresent,
        @ApiOptional EmbeddingProvenanceView embeddingProvenance,
        List<CitationUsageView> citationUsages,
        long citationUsageCount,
        @ApiOptional String citationUsagesNextCursor,
        List<RetrievalUsageView> retrievalUsages,
        long retrievalUsageCount,
        @ApiOptional String retrievalUsagesNextCursor,
        OffsetDateTime createdAt
    ) {}

    public record EmbeddingProvenanceView(
        String provider,
        String model,
        int dimension,
        String modelDigest,
        String queryInstruction,
        String indexProfileFingerprint,
        boolean productionReady
    ) {}

    public record RetrievalHitView(
        String knowledgeUnitId,
        String documentId,
        String sourceId,
        String title,
        String content,
        @ApiOptional Integer keywordRank,
        @ApiOptional Double keywordScore,
        @ApiOptional Integer vectorRank,
        @ApiOptional Double vectorScore,
        int fusionRank,
        double fusionScore,
        double applicabilityScore,
        String applicabilityReason,
        List<String> expectedPredicates,
        boolean selected,
        String selectionReason,
        JsonNode sourceLocator,
        String sourceVersion,
        String contentHash
    ) {}

    public record RetrievalIntentView(
        String type,
        String query,
        List<RetrievalHitView> hits
    ) {}

    public record RetrievalRunView(
        String id,
        @ApiOptional String investigationRunId,
        String indexVersion,
        String embeddingProvider,
        String embeddingModel,
        String embeddingModelDigest,
        String indexProfileFingerprint,
        String status,
        JsonNode queryPlan,
        JsonNode retrievalConfig,
        List<RetrievalIntentView> intents,
        String contextHash,
        OffsetDateTime createdAt
    ) {}
}
