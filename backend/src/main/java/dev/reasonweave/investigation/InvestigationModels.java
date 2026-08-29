package dev.reasonweave.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.config.ApiOptional;
import java.time.OffsetDateTime;
import java.util.List;

public final class InvestigationModels {
    private InvestigationModels() {}

    public record InvestigationRunView(
        String id,
        String eventId,
        int sequenceNo,
        String status,
        long eventVersion,
        int evidenceSnapshotSchemaVersion,
        String evidenceSnapshotHash,
        EvidenceSnapshotView evidenceSnapshot,
        String modelPolicyVersion,
        String rulePackVersion,
        String domainPackKey,
        String domainPackVersion,
        String domainPackFingerprint,
        String knowledgeIndexVersion,
        @ApiOptional String retrievalRunId,
        JsonNode eventIrSnapshot,
        @ApiOptional InvestigationResultView result,
        boolean stale,
        OffsetDateTime startedAt,
        @ApiOptional OffsetDateTime completedAt,
        @ApiOptional String errorCode,
        @ApiOptional String errorMessage,
        OffsetDateTime createdAt
    ) {}

    public record InvestigationPage(
        List<InvestigationRunView> items,
        @ApiOptional String nextCursor,
        int limit,
        long total
    ) {}

    public record EvidenceSnapshotView(
        int schemaVersion,
        List<String> evidenceIds,
        List<EvidenceSnapshotItemView> evidence
    ) {}

    public record EvidenceSnapshotItemView(
        String id,
        String type,
        String source,
        String status,
        @ApiOptional String originalName,
        @ApiOptional String contentType,
        @ApiOptional String checksumSha256,
        int generation,
        double reliability,
        OffsetDateTime createdAt,
        List<ObservationSnapshotView> observations
    ) {}

    public record ObservationSnapshotView(
        String id,
        int generation,
        long version,
        String predicate,
        Object value,
        @ApiOptional String description,
        double modelConfidence,
        String verificationStatus,
        boolean present
    ) {}

    public record ExpectedEvidenceView(
        String predicate,
        double weight,
        String relation,
        boolean required,
        String origin,
        String ruleId
    ) {}

    public record ContributionView(
        String ruleId,
        String ruleVersion,
        String predicate,
        String relation,
        double ruleWeight,
        String evidenceId,
        String observationId,
        double sourceReliability,
        double extractionConfidence,
        double relevance,
        double value,
        String reason
    ) {}

    public record HypothesisResultView(
        String id,
        String code,
        String title,
        String description,
        int score,
        String band,
        double coverage,
        double positive,
        double negative,
        double missingPenalty,
        List<ExpectedEvidenceView> expectedEvidence,
        List<ContributionView> contributions,
        List<String> citationIds,
        String groundingStatus,
        List<String> knowledgeLimitations
    ) {}

    public record NextEvidenceView(
        String id,
        @ApiOptional String recommendationId,
        String evidenceType,
        String title,
        @ApiOptional String expectedPredicate,
        String reason,
        List<String> discriminates,
        String estimatedImpact,
        String acquisitionCost,
        double priorityScore,
        String status,
        String source,
        OffsetDateTime createdAt
    ) {}

    public record InvestigationResultView(
        String supportIndexDisclaimer,
        String pipeline,
        String plannerVersion,
        long eventVersion,
        String evidenceSnapshotHash,
        String knowledgeIndexVersion,
        String retrievalRunId,
        String knowledgeContextHash,
        EvidenceSnapshotView evidenceSnapshot,
        List<HypothesisResultView> hypotheses,
        List<NextEvidenceView> nextEvidence
    ) {}

    public record QueryIntentView(String type, String query) {}

    public record QueryPlanView(
        String domainPackKey,
        String plannerVersion,
        int keywordTopK,
        int vectorTopK,
        int finalTopK,
        List<QueryIntentView> intents
    ) {}

    public record KnowledgeCitationView(
        String id,
        String knowledgeUnitId,
        String targetType,
        String targetId,
        JsonNode sourceLocator,
        String sourceVersion,
        String contentHash,
        String usageReason,
        OffsetDateTime createdAt
    ) {}

    public record KnowledgeContextView(
        String investigationRunId,
        String retrievalRunId,
        String indexVersion,
        String contextHash,
        QueryPlanView queryPlan,
        List<KnowledgeCitationView> citations
    ) {}

    public record HypothesisChangeView(
        String code,
        String title,
        @ApiOptional Integer beforeScore,
        @ApiOptional Integer afterScore,
        @ApiOptional Integer scoreDelta,
        @ApiOptional Double beforeCoverage,
        @ApiOptional Double afterCoverage
    ) {}

    public record RunDiffView(
        String baseRunId,
        String currentRunId,
        int eventVersionDelta,
        boolean evidenceSnapshotChanged,
        boolean knowledgeIndexChanged,
        List<HypothesisChangeView> hypothesisChanges,
        List<String> addedEvidenceIds,
        List<String> removedEvidenceIds
    ) {}
}
