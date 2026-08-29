package dev.reasonweave.domainpack;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.config.ApiOptional;
import java.util.List;

public final class DomainPackModels {
    private DomainPackModels() {}

    public record DomainPackSummary(
        String key,
        String version,
        String name,
        String description,
        String status,
        boolean fixtureOnly,
        boolean productionAllowed,
        String compatibleEventir,
        int hypothesisCount,
        int ruleCount,
        int documentCount,
        int unitCount,
        @ApiOptional String knowledgeSourceId,
        @ApiOptional String knowledgeIndexVersion,
        String presentationLocale,
        String fingerprint,
        List<String> eventTypes,
        String vectorPolicy,
        JsonNode observationBundle,
        @ApiOptional TargetVersionView supportedTargetVersions,
        JsonNode sourceProfiles,
        JsonNode licenses,
        boolean ready,
        List<String> readinessReasons
    ) {}

    public record DomainPackDetail(
        DomainPackSummary summary,
        JsonNode manifest,
        List<EventTypeView> eventDefinitions,
        JsonNode vocabulary,
        JsonNode presentation,
        JsonNode hypotheses,
        JsonNode rules,
        JsonNode nextEvidence,
        JsonNode retrievalConfig,
        JsonNode knowledgeMetadata,
        List<String> warnings
    ) {}

    public record EventTypeView(
        String domainPack,
        String eventType,
        String subjectType,
        List<String> identityFields,
        String labelTemplate,
        JsonNode attributesSchema,
        EventRequirementsView eventRequirements,
        List<EvidenceInputView> evidenceInputs,
        @ApiOptional TargetVersionView targetVersions,
        EventPresentationView presentation
    ) {}

    public record EventRequirementsView(
        String timeRange
    ) {}

    public record EvidenceInputView(
        String type,
        boolean enabled,
        @ApiOptional String sourceProfile,
        @ApiOptional Double sourceReliability,
        @ApiOptional String predicate,
        @ApiOptional String verificationStatus,
        boolean requiresHumanConfirmation,
        List<String> contentTypes,
        @ApiOptional String label,
        @ApiOptional String help,
        @ApiOptional String collectorCommand
    ) {}

    public record EventPresentationView(
        String label,
        String subjectLabel,
        List<FieldPresentationView> fields
    ) {}

    public record FieldPresentationView(
        String name,
        String label,
        String control,
        @ApiOptional String placeholder,
        boolean required,
        List<FieldOptionView> options
    ) {}

    public record FieldOptionView(
        String value,
        String label
    ) {}

    public record TargetVersionView(
        String scheme,
        @ApiOptional String minimum,
        @ApiOptional String maximumExclusive
    ) {}
}
