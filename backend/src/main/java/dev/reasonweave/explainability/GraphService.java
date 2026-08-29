package dev.reasonweave.explainability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reasonweave.investigation.InvestigationModels;
import dev.reasonweave.investigation.InvestigationService;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.JsonSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class GraphService {
    private final JdbcClient jdbc;
    private final InvestigationService investigations;
    private final JsonSupport json;
    private final ObjectMapper mapper;

    public GraphService(
        JdbcClient jdbc,
        InvestigationService investigations,
        JsonSupport json,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.investigations = investigations;
        this.json = json;
        this.mapper = mapper;
    }

    public GraphModels.GraphView get(String eventId, String investigationId) {
        InvestigationModels.InvestigationRunView run = investigations.get(investigationId);
        if (!run.eventId().equals(eventId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "该事件下不存在指定调查运行");
        }
        Map<String, GraphModels.GraphNode> nodes = new LinkedHashMap<>();
        List<GraphModels.GraphEdge> edges = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        if (!"COMPLETED".equals(run.status())) {
            warnings.add("指定调查运行尚未完整完成，图谱可能只包含部分节点。");
        }
        JsonNode eventIr = run.eventIrSnapshot();
        JsonNode event = eventIr.path("event");
        String eventNodeId = nodeId("event", eventId);
        ObjectNode eventMetadata = mapper.createObjectNode();
        eventMetadata.put("event_version", run.eventVersion());
        eventMetadata.put("reference_code", event.path("reference_code").asText(""));
        eventMetadata.put("event_type", event.path("type").asText(""));
        nodes.put(eventNodeId, new GraphModels.GraphNode(
            eventNodeId, eventId, "EVENT", event.path("title").asText("未命名事件"),
            event.path("reference_code").asText(""), run.status(), null, null, eventMetadata
        ));

        for (JsonNode subject : eventIr.path("subjects")) {
            String subjectId = subject.path("id").asText();
            if (subjectId.isBlank()) {
                continue;
            }
            String id = nodeId("subject", subjectId);
            nodes.put(id, new GraphModels.GraphNode(
                id, subjectId, "SUBJECT", subject.path("label").asText(subjectId),
                subject.path("type").asText(""), null, null, null, subject.deepCopy()
            ));
            edges.add(edge("subject-event", subjectId, eventId, id, eventNodeId,
                "RELATES_TO", null, false, "调查对象属于该事件", mapper.createObjectNode()));
        }

        Map<String, String> evidenceLabels = evidenceLabels(eventIr, run.evidenceSnapshot());
        Map<String, InvestigationModels.ObservationSnapshotView> observationSnapshots = observationSnapshots(
            run.evidenceSnapshot()
        );
        for (String evidenceId : run.evidenceSnapshot().evidenceIds()) {
            addEvidenceNode(nodes, evidenceId, evidenceLabels);
        }

        List<HypothesisRow> hypotheses = hypothesisRows(investigationId);
        Map<String, HypothesisRow> hypothesisById = new HashMap<>();
        Map<String, List<String>> hypothesisIdsByCode = new HashMap<>();
        for (HypothesisRow hypothesis : hypotheses) {
            hypothesisById.put(hypothesis.id(), hypothesis);
            hypothesisIdsByCode.computeIfAbsent(hypothesis.code(), ignored -> new ArrayList<>()).add(hypothesis.id());
            String id = nodeId("hypothesis", hypothesis.id());
            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("code", hypothesis.code());
            metadata.put("description", hypothesis.description());
            metadata.set("expected_evidence", hypothesis.expectedEvidence());
            nodes.put(id, new GraphModels.GraphNode(
                id, hypothesis.id(), "HYPOTHESIS", hypothesis.title(), hypothesis.code(),
                hypothesis.scoreBand(), (double) hypothesis.score(), hypothesis.coverage(), metadata
            ));
            edges.add(edge("hypothesis-event", hypothesis.id(), eventId, id, eventNodeId,
                "EXPLAINS", null, false, "原因假设用于解释事件", mapper.createObjectNode()));
        }

        for (ContributionRow contribution : contributionRows(investigationId)) {
            HypothesisRow hypothesis = hypothesisById.get(contribution.hypothesisId());
            if (hypothesis == null) {
                continue;
            }
            addEvidenceNode(nodes, contribution.evidenceId(), evidenceLabels);
            InvestigationModels.ObservationSnapshotView observationSnapshot = observationSnapshots.get(
                contribution.observationId()
            );
            String predicate = observationSnapshot == null
                ? predicateFor(hypothesis.expectedEvidence(), contribution.ruleId())
                : observationSnapshot.predicate();
            String observationNodeId = nodeId("observation", contribution.observationId());
            ObjectNode observationMetadata = mapper.createObjectNode();
            observationMetadata.put("predicate", predicate);
            observationMetadata.put("rule_id", contribution.ruleId());
            observationMetadata.put("rule_version", contribution.ruleVersion());
            nodes.put(observationNodeId, new GraphModels.GraphNode(
                observationNodeId, contribution.observationId(), "OBSERVATION", predicate,
                observationSnapshot == null ? contribution.reason() : observationSnapshot.description(),
                observationSnapshot == null ? null : observationSnapshot.verificationStatus(),
                null, null, observationMetadata
            ));
            edges.add(edge(
                "evidence-observation", contribution.evidenceId(), contribution.observationId(),
                nodeId("evidence", contribution.evidenceId()), observationNodeId,
                "OBSERVED_FROM", null, false, "该观察来自证据快照", mapper.createObjectNode()
            ));
            ObjectNode relationMetadata = mapper.createObjectNode();
            relationMetadata.put("relation", contribution.relation());
            relationMetadata.put("rule_id", contribution.ruleId());
            relationMetadata.put("rule_weight", contribution.ruleWeight());
            relationMetadata.put("source_reliability", contribution.sourceReliability());
            relationMetadata.put("extraction_confidence", contribution.extractionConfidence());
            relationMetadata.put("relevance", contribution.relevance());
            String relationType = contribution.contribution() > 0 ? "SUPPORTS"
                : contribution.contribution() < 0 ? "CONTRADICTS" : "RELATES_TO";
            edges.add(edge(
                "observation-hypothesis", contribution.observationId(), contribution.hypothesisId(),
                observationNodeId, nodeId("hypothesis", contribution.hypothesisId()),
                relationType, contribution.contribution(), true, contribution.reason(), relationMetadata
            ));
        }

        for (CitationRow citation : citationRows(investigationId)) {
            String knowledgeNodeId = nodeId("knowledge", citation.knowledgeUnitId());
            String section = citation.sourceLocator().path("section").asText();
            String document = citation.sourceLocator().path("document_id").asText(citation.knowledgeUnitId());
            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("citation_id", citation.id());
            metadata.put("source_version", citation.sourceVersion());
            metadata.put("content_hash", citation.contentHash());
            metadata.set("source_locator", citation.sourceLocator());
            metadata.put("usage_reason", citation.usageReason());
            nodes.putIfAbsent(knowledgeNodeId, new GraphModels.GraphNode(
                knowledgeNodeId, citation.knowledgeUnitId(), "KNOWLEDGE",
                section.isBlank() ? document : section, document, null, null, null, metadata
            ));
            edges.add(edge(
                "knowledge-hypothesis", citation.id(), citation.targetId(), knowledgeNodeId,
                nodeId("hypothesis", citation.targetId()), "GROUNDED_BY", null, false,
                citation.usageReason(), metadata.deepCopy()
            ));
            String currentHash = currentKnowledgeHash(citation.knowledgeUnitId());
            if (currentHash == null) {
                warnings.add("知识单元 " + citation.knowledgeUnitId() + " 当前不可用；图谱仍使用调查时引用快照。");
            } else if (!currentHash.equals(citation.contentHash())) {
                warnings.add("知识单元 " + citation.knowledgeUnitId() + " 已发生变化；图谱仍使用调查时内容 Hash。");
            }
        }

        for (GapRow gap : gapRows(investigationId)) {
            String gapNodeId = nodeId("gap", gap.id());
            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("evidence_type", gap.evidenceType());
            metadata.put("estimated_impact", gap.estimatedImpact());
            metadata.put("acquisition_cost", gap.acquisitionCost());
            metadata.put("priority_score", gap.priorityScore());
            metadata.set("discriminates", gap.discriminates());
            nodes.put(gapNodeId, new GraphModels.GraphNode(
                gapNodeId, gap.id(), "GAP", gap.title(), gap.reason(), gap.status(),
                gap.priorityScore(), null, metadata
            ));
            for (JsonNode code : gap.discriminates()) {
                for (String hypothesisId : hypothesisIdsByCode.getOrDefault(code.asText(), List.of())) {
                    edges.add(edge(
                        "gap-hypothesis", gap.id(), hypothesisId, gapNodeId,
                        nodeId("hypothesis", hypothesisId), "MISSING_FOR", null, false,
                        gap.reason(), mapper.createObjectNode()
                    ));
                }
            }
        }

        return new GraphModels.GraphView(
            eventId, investigationId, run.stale(), List.copyOf(warnings),
            List.copyOf(nodes.values()), List.copyOf(edges)
        );
    }

    private List<HypothesisRow> hypothesisRows(String runId) {
        return jdbc.sql("""
                select id, code, title, description, score, score_band,
                       evidence_coverage, expected_evidence::text
                from hypotheses
                where investigation_run_id = :runId
                order by score desc, id
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new HypothesisRow(
                rs.getString("id"), rs.getString("code"), rs.getString("title"),
                rs.getString("description"), rs.getInt("score"), rs.getString("score_band"),
                rs.getDouble("evidence_coverage"), json.read(rs.getString("expected_evidence"))
            ))
            .list();
    }

    private List<ContributionRow> contributionRows(String runId) {
        return jdbc.sql("""
                select he.hypothesis_id, he.evidence_id, he.observation_id, he.relation,
                       he.rule_weight, he.source_reliability, he.extraction_confidence,
                       he.relevance, he.contribution, he.reason, he.rule_id, he.rule_version
                from hypothesis_evidence he
                join hypotheses h on h.id = he.hypothesis_id
                where h.investigation_run_id = :runId
                order by he.hypothesis_id, he.observation_id, he.rule_id
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new ContributionRow(
                rs.getString("hypothesis_id"), rs.getString("evidence_id"),
                rs.getString("observation_id"), rs.getString("relation"),
                rs.getDouble("rule_weight"), rs.getDouble("source_reliability"),
                rs.getDouble("extraction_confidence"), rs.getDouble("relevance"),
                rs.getDouble("contribution"), rs.getString("reason"),
                rs.getString("rule_id"), rs.getString("rule_version")
            ))
            .list();
    }

    private List<CitationRow> citationRows(String runId) {
        return jdbc.sql("""
                select id, knowledge_unit_id, target_id, source_locator::text,
                       source_version, content_hash, usage_reason
                from knowledge_citations
                where investigation_run_id = :runId and target_type = 'HYPOTHESIS'
                order by id
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new CitationRow(
                rs.getString("id"), rs.getString("knowledge_unit_id"), rs.getString("target_id"),
                json.read(rs.getString("source_locator")), rs.getString("source_version"),
                rs.getString("content_hash"), rs.getString("usage_reason")
            ))
            .list();
    }

    private List<GapRow> gapRows(String runId) {
        return jdbc.sql("""
                select id, evidence_type, title, reason, discriminates::text,
                       estimated_impact, acquisition_cost, priority_score, status
                from evidence_gaps
                where investigation_run_id = :runId
                order by priority_score desc, id
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new GapRow(
                rs.getString("id"), rs.getString("evidence_type"), rs.getString("title"),
                rs.getString("reason"), json.read(rs.getString("discriminates")),
                rs.getString("estimated_impact"), rs.getString("acquisition_cost"),
                rs.getDouble("priority_score"), rs.getString("status")
            ))
            .list();
    }

    private String currentKnowledgeHash(String unitId) {
        return jdbc.sql("select content_hash from knowledge_units where id = :id")
            .param("id", unitId)
            .query(String.class)
            .optional()
            .orElse(null);
    }

    private static void addEvidenceNode(
        Map<String, GraphModels.GraphNode> nodes,
        String evidenceId,
        Map<String, String> labels
    ) {
        if (evidenceId == null || evidenceId.isBlank()) {
            return;
        }
        String id = nodeId("evidence", evidenceId);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("snapshot_identity_only", true);
        nodes.putIfAbsent(id, new GraphModels.GraphNode(
            id, evidenceId, "EVIDENCE", labels.getOrDefault(evidenceId, "证据 " + shortId(evidenceId)),
            "调查时证据快照", null, null, null, metadata
        ));
    }

    private static Map<String, String> evidenceLabels(
        JsonNode eventIr,
        InvestigationModels.EvidenceSnapshotView snapshot
    ) {
        Map<String, String> labels = new HashMap<>();
        for (JsonNode evidence : eventIr.path("evidence")) {
            String id = evidence.path("id").asText();
            if (!id.isBlank()) {
                labels.put(id, evidence.path("label").asText(
                    evidence.path("type").asText("证据") + " " + shortId(id)
                ));
            }
        }
        for (InvestigationModels.EvidenceSnapshotItemView evidence : snapshot.evidence()) {
            String label = evidence.originalName();
            if (label == null || label.isBlank()) {
                label = evidence.type() + " " + shortId(evidence.id());
            }
            labels.put(evidence.id(), label);
        }
        return labels;
    }

    private static Map<String, InvestigationModels.ObservationSnapshotView> observationSnapshots(
        InvestigationModels.EvidenceSnapshotView snapshot
    ) {
        Map<String, InvestigationModels.ObservationSnapshotView> values = new HashMap<>();
        for (InvestigationModels.EvidenceSnapshotItemView evidence : snapshot.evidence()) {
            for (InvestigationModels.ObservationSnapshotView observation : evidence.observations()) {
                values.put(observation.id(), observation);
            }
        }
        return values;
    }

    private static String predicateFor(JsonNode expectedEvidence, String ruleId) {
        for (JsonNode expected : expectedEvidence) {
            if (ruleId.equals(expected.path("rule_id").asText())) {
                return expected.path("predicate").asText("unknown_predicate");
            }
        }
        return "unknown_predicate";
    }

    private static GraphModels.GraphEdge edge(
        String prefix,
        String left,
        String right,
        String source,
        String target,
        String type,
        Double contribution,
        boolean scoreAffecting,
        String explanation,
        JsonNode metadata
    ) {
        return new GraphModels.GraphEdge(
            prefix + ":" + left + ":" + right, source, target, type, contribution,
            scoreAffecting, explanation, metadata
        );
    }

    private static String nodeId(String type, String id) {
        return type + ":" + id;
    }

    private static String shortId(String id) {
        return id.length() <= 10 ? id : id.substring(id.length() - 10);
    }

    private record HypothesisRow(
        String id, String code, String title, String description, int score,
        String scoreBand, double coverage, JsonNode expectedEvidence
    ) {}

    private record ContributionRow(
        String hypothesisId, String evidenceId, String observationId, String relation,
        double ruleWeight, double sourceReliability, double extractionConfidence,
        double relevance, double contribution, String reason, String ruleId, String ruleVersion
    ) {}

    private record CitationRow(
        String id, String knowledgeUnitId, String targetId, JsonNode sourceLocator,
        String sourceVersion, String contentHash, String usageReason
    ) {}

    private record GapRow(
        String id, String evidenceType, String title, String reason, JsonNode discriminates,
        String estimatedImpact, String acquisitionCost, double priorityScore, String status
    ) {}
}
