package dev.reasonweave.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reasonweave.audit.AuditService;
import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.domainpack.DomainPackCatalog;
import dev.reasonweave.domainpack.DomainPackDefinition;
import dev.reasonweave.domainpack.DomainPackRegistry;
import dev.reasonweave.event.EventModels;
import dev.reasonweave.event.EventService;
import dev.reasonweave.investigation.ScoringEngine.Contribution;
import dev.reasonweave.investigation.ScoringEngine.EvidenceFact;
import dev.reasonweave.investigation.ScoringEngine.ExpectedRule;
import dev.reasonweave.investigation.ScoringEngine.ScoreResult;
import dev.reasonweave.knowledge.KnowledgeModels;
import dev.reasonweave.knowledge.KnowledgeService;
import dev.reasonweave.knowledge.KnowledgeService.QueryIntent;
import dev.reasonweave.model.ModelGateway.ModelGatewayException;
import dev.reasonweave.model.ModelGateway;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.Hashing;
import dev.reasonweave.shared.IdempotencyService;
import dev.reasonweave.shared.JsonSupport;
import dev.reasonweave.shared.PageCursor;
import dev.reasonweave.shared.ids.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvestigationService {
    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final String MODEL_POLICY_VERSION = "grounded-policy-v1";
    private static final String PLANNER_VERSION = "query-plan-v1";
    private static final int EVIDENCE_SNAPSHOT_SCHEMA_VERSION = 2;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final JsonSupport json;
    private final ObjectMapper mapper;
    private final EventService events;
    private final KnowledgeService knowledge;
    private final DomainPackRegistry domainPacks;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final ReasonWeaveProperties properties;
    private final ModelGateway model;
    private final TransactionTemplate transaction;
    private final Timer duration;
    private final ScoringEngine scoring = new ScoringEngine();

    public InvestigationService(
        JdbcClient jdbc,
        IdGenerator ids,
        JsonSupport json,
        ObjectMapper mapper,
        EventService events,
        KnowledgeService knowledge,
        DomainPackRegistry domainPacks,
        AuditService audit,
        IdempotencyService idempotency,
        ReasonWeaveProperties properties,
        ModelGateway model,
        PlatformTransactionManager transactionManager,
        MeterRegistry meterRegistry
    ) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
        this.mapper = mapper;
        this.events = events;
        this.knowledge = knowledge;
        this.domainPacks = domainPacks;
        this.audit = audit;
        this.idempotency = idempotency;
        this.properties = properties;
        this.model = model;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.duration = meterRegistry.timer("reasonweave.investigation.duration");
    }

    public InvestigationModels.InvestigationRunView start(
        String eventId,
        String idempotencyKey,
        String requestId
    ) {
        String endpoint = "POST /api/v1/events/" + eventId + "/investigations";
        String requestHash = Hashing.sha256(json.canonicalWrite(Map.of("event_id", eventId)));
        BeginRun begin = transaction.execute(status -> beginRun(
            eventId, endpoint, idempotencyKey, requestHash, requestId
        ));
        if (begin == null) {
            throw new IllegalStateException("Investigation transaction returned no result");
        }
        if (begin.replayRunId() != null) {
            return requireTerminal(begin.replayRunId());
        }

        Timer.Sample sample = Timer.start();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("run_id", begin.runId())) {
            List<QueryIntent> queryPlan = buildQueryPlan(begin.event(), begin.evidenceSnapshot().facts());
            KnowledgeModels.RetrievalRunView retrieval = knowledge.retrieve(
                queryPlan,
                begin.packKey(),
                begin.runId(),
                begin.event().eventType(),
                begin.evidenceSnapshot().facts().stream()
                    .filter(EvidenceFact::present)
                    .map(EvidenceFact::predicate)
                    .collect(Collectors.toCollection(LinkedHashSet::new))
            );
            transaction.executeWithoutResult(status -> recordRetrieval(begin, retrieval, requestId));
            InvestigationModels.InvestigationRunView completed = transaction.execute(status -> completeRun(
                begin,
                retrieval,
                requestId
            ));
            if (completed == null) {
                throw new IllegalStateException("Investigation completion returned no result");
            }
            return completed;
        } catch (RuntimeException exception) {
            boolean providerFailure = causedBy(exception, ModelGatewayException.class);
            HttpStatus status = providerFailure ? HttpStatus.BAD_GATEWAY : HttpStatus.INTERNAL_SERVER_ERROR;
            String code = providerFailure ? "INVESTIGATION_PROVIDER_FAILED" : "INVESTIGATION_FAILED";
            String message = providerFailure
                ? "模型或嵌入服务处理失败"
                : "调查流水线执行失败";
            transaction.executeWithoutResult(transactionStatus -> failRun(
                begin,
                status.value(),
                code,
                message,
                requestId
            ));
            log.error(
                "Investigation failed request_id={} run_id={} event_id={} code={}",
                requestId,
                begin.runId(),
                eventId,
                code,
                exception
            );
            throw new ApiException(status, code, message, Map.of("run_id", begin.runId()));
        } finally {
            sample.stop(duration);
        }
    }

    private BeginRun beginRun(
        String eventId,
        String endpoint,
        String idempotencyKey,
        String requestHash,
        String requestId
    ) {
        String lockedEventId = jdbc.sql("""
                select id from events
                where id = :eventId and workspace_id = :workspaceId
                for update
                """)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .query(String.class)
            .optional()
            .orElse(null);
        if (lockedEventId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "事件不存在");
        }

        EventModels.EventDetail event = events.get(eventId);
        events.assertSupported(event);
        IdempotencyService.Claim claim = idempotency.claim(
            InstanceScope.ID,
            endpoint,
            idempotencyKey,
            requestHash
        );
        if (!claim.claimed()) {
            if (claim.resourceId() == null) {
                throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_IN_PROGRESS",
                    "相同幂等请求仍在初始化，请稍后重试",
                    Map.of("endpoint", endpoint)
                );
            }
            return BeginRun.replay(claim.resourceId());
        }

        EvidenceSnapshot evidenceSnapshot = evidenceSnapshot(eventId);
        DomainPackDefinition domainPack = domainPacks.requireForEvent(
            event.domainPackKey(), event.eventType()
        );
        knowledge.assertReadyForInvestigation(domainPack, properties.seedFixtures());
        String packVersion = domainPack.scopedKey() + "@"
            + domainPack.fingerprint().substring(0, 16);
        String packKey = event.domainPackKey();
        String indexVersion = knowledge.currentIndexVersion(packKey);
        int sequence = nextSequence(eventId);
        String runId = ids.next("inv");

        jdbc.sql("""
                insert into investigation_runs(
                    id, event_id, workspace_id, sequence_no, status, event_version,
                    evidence_snapshot_schema_version, evidence_snapshot_hash, evidence_snapshot,
                    model_policy_version, rule_pack_version, domain_pack_key,
                    domain_pack_version, domain_pack_fingerprint, knowledge_index_version,
                    event_ir_snapshot, started_at
                ) values (
                    :id, :eventId, :workspaceId, :sequence, 'RUNNING', :eventVersion,
                    :snapshotVersion, :evidenceHash, cast(:evidenceSnapshot as jsonb),
                    :modelPolicyVersion, :rulePackVersion, :domainPackKey,
                    :domainPackVersion, :domainPackFingerprint, :indexVersion,
                    cast(:eventIr as jsonb), now()
                )
                """)
            .param("id", runId)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("sequence", sequence)
            .param("eventVersion", event.version())
            .param("snapshotVersion", EVIDENCE_SNAPSHOT_SCHEMA_VERSION)
            .param("evidenceHash", evidenceSnapshot.hash())
            .param("evidenceSnapshot", json.write(evidenceSnapshot.view()))
            .param("modelPolicyVersion", MODEL_POLICY_VERSION)
            .param("rulePackVersion", packVersion)
            .param("domainPackKey", domainPack.key())
            .param("domainPackVersion", domainPack.version())
            .param("domainPackFingerprint", domainPack.fingerprint())
            .param("indexVersion", indexVersion)
            .param("eventIr", json.write(event.eventIr()))
            .update();
        idempotency.attachResource(InstanceScope.ID, endpoint, claim.key(), runId);
        audit.record(eventId, "investigation.started", "investigation_run", runId, null, Map.of(
            "investigation_run_id", runId,
            "sequence_no", sequence,
            "event_version", event.version(),
            "evidence_snapshot_schema_version", EVIDENCE_SNAPSHOT_SCHEMA_VERSION,
            "evidence_snapshot_hash", evidenceSnapshot.hash()
        ), requestId);
        return BeginRun.created(
            event,
            evidenceSnapshot,
            domainPack,
            packKey,
            runId,
            endpoint,
            claim.key()
        );
    }

    private void recordRetrieval(
        BeginRun begin,
        KnowledgeModels.RetrievalRunView retrieval,
        String requestId
    ) {
        jdbc.sql("update investigation_runs set retrieval_run_id = :retrievalId where id = :id and status = 'RUNNING'")
            .param("retrievalId", retrieval.id())
            .param("id", begin.runId())
            .update();
        audit.record(begin.event().id(), "retrieval.completed", "investigation_run", begin.runId(), null, Map.of(
            "investigation_run_id", begin.runId(),
            "retrieval_run_id", retrieval.id(),
            "index_version", retrieval.indexVersion(),
            "context_hash", retrieval.contextHash()
        ), requestId);
    }

    private InvestigationModels.InvestigationRunView completeRun(
        BeginRun begin,
        KnowledgeModels.RetrievalRunView retrieval,
        String requestId
    ) {
        List<HypothesisResult> hypotheses = buildHypotheses(
            begin.runId(),
            begin.evidenceSnapshot().facts(),
            retrieval,
            begin.domainPack().content()
        );
        audit.record(begin.event().id(), "hypotheses.generated", "investigation_run", begin.runId(), null, Map.of(
            "investigation_run_id", begin.runId(),
            "hypothesis_count", hypotheses.size(),
            "hypothesis_ids", hypotheses.stream().map(HypothesisResult::id).toList()
        ), requestId);
        buildGaps(
            begin.runId(), hypotheses, begin.evidenceSnapshot().facts(), begin.domainPack().content()
        );
        List<InvestigationModels.NextEvidenceView> gaps = loadNextEvidence(begin.runId());
        InvestigationModels.InvestigationResultView result = resultSnapshot(
            begin.event(),
            begin.evidenceSnapshot(),
            retrieval,
            hypotheses,
            gaps
        );
        int updated = jdbc.sql("""
                update investigation_runs
                set status = 'COMPLETED', result_snapshot = cast(:result as jsonb), completed_at = now()
                where id = :id and status = 'RUNNING'
                """)
            .param("result", json.write(result))
            .param("id", begin.runId())
            .update();
        if (updated != 1) {
            throw new IllegalStateException("Investigation run is no longer in RUNNING state");
        }
        jdbc.sql("update events set status = 'INVESTIGATING', updated_at = now() where id = :eventId")
            .param("eventId", begin.event().id())
            .update();
        audit.record(
            begin.event().id(),
            "investigation.completed",
            "investigation_run",
            begin.runId(),
            null,
            result,
            requestId
        );
        InvestigationModels.InvestigationRunView completed = get(begin.runId());
        idempotency.complete(
            InstanceScope.ID,
            begin.endpoint(),
            begin.idempotencyKey(),
            201,
            completed
        );
        return completed;
    }

    private void failRun(
        BeginRun begin,
        int responseStatus,
        String code,
        String message,
        String requestId
    ) {
        jdbc.sql("""
                update investigation_runs
                set status = 'FAILED', error_code = :code,
                    error_message = :message, completed_at = now()
                where id = :id and status = 'RUNNING'
                """)
            .param("code", code)
            .param("message", message)
            .param("id", begin.runId())
            .update();
        audit.record(begin.event().id(), "investigation.failed", "investigation_run", begin.runId(), null, Map.of(
            "investigation_run_id", begin.runId(),
            "error_code", code,
            "error_message", message
        ), requestId);
        InvestigationModels.InvestigationRunView failed = get(begin.runId());
        idempotency.complete(
            InstanceScope.ID,
            begin.endpoint(),
            begin.idempotencyKey(),
            responseStatus,
            failed
        );
    }

    private InvestigationModels.InvestigationRunView requireTerminal(String runId) {
        InvestigationModels.InvestigationRunView run = get(runId);
        if ("RUNNING".equals(run.status())) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_IN_PROGRESS",
                "相同幂等请求仍在处理中，请稍后重试",
                Map.of("run_id", runId)
            );
        }
        if ("FAILED".equals(run.status())) {
            HttpStatus status = "INVESTIGATION_PROVIDER_FAILED".equals(run.errorCode())
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.INTERNAL_SERVER_ERROR;
            throw new ApiException(
                status,
                run.errorCode() == null ? "INVESTIGATION_FAILED" : run.errorCode(),
                run.errorMessage() == null ? "调查流水线执行失败" : run.errorMessage(),
                Map.of("run_id", runId)
            );
        }
        return run;
    }

    private static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public InvestigationModels.InvestigationRunView get(String id) {
        return jdbc.sql("""
                select * from investigation_runs
                where id = :id and workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query(this::mapRun)
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "调查运行不存在"));
    }

    public InvestigationModels.InvestigationPage listForEvent(
        String eventId,
        String cursor,
        int requestedLimit
    ) {
        events.get(eventId);
        int limit = PageCursor.limit(requestedLimit);
        String scope = Hashing.sha256(eventId);
        List<String> decoded = PageCursor.decode(cursor, scope, 2);
        int cursorSequence = Integer.MAX_VALUE;
        String cursorId = "\uffff";
        if (!decoded.isEmpty()) {
            try {
                cursorSequence = Integer.parseInt(decoded.get(0));
                cursorId = decoded.get(1);
            } catch (RuntimeException exception) {
                throw PageCursor.invalidCursor();
            }
        }
        List<InvestigationModels.InvestigationRunView> rows = jdbc.sql("""
                select * from investigation_runs
                where event_id = :eventId and workspace_id = :workspaceId
                  and (sequence_no, id) < (:cursorSequence, :cursorId)
                order by sequence_no desc, id desc
                limit :limit
                """)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("cursorSequence", cursorSequence)
            .param("cursorId", cursorId)
            .param("limit", limit + 1)
            .query(this::mapRun)
            .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            InvestigationModels.InvestigationRunView boundary = rows.get(limit - 1);
            nextCursor = PageCursor.encode(
                scope,
                Integer.toString(boundary.sequenceNo()),
                boundary.id()
            );
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        long total = jdbc.sql("""
                select count(*) from investigation_runs
                where event_id = :eventId and workspace_id = :workspaceId
                """)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .query(Long.class)
            .single();
        return new InvestigationModels.InvestigationPage(List.copyOf(rows), nextCursor, limit, total);
    }

    public InvestigationModels.KnowledgeContextView knowledgeContext(String runId) {
        InvestigationModels.InvestigationRunView run = get(runId);
        if (run.retrievalRunId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RETRIEVAL_NOT_READY", "本次调查尚无检索快照");
        }
        KnowledgeModels.RetrievalRunView retrieval = knowledge.getRetrievalRun(run.retrievalRunId());
        List<InvestigationModels.KnowledgeCitationView> citations = jdbc.sql("""
                select c.id, c.knowledge_unit_id, c.target_type, c.target_id,
                       c.source_locator::text, c.source_version, c.content_hash,
                       c.usage_reason, c.created_at
                from knowledge_citations c
                where c.investigation_run_id = :runId
                order by c.created_at, c.id
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new InvestigationModels.KnowledgeCitationView(
                rs.getString("id"),
                rs.getString("knowledge_unit_id"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                json.read(rs.getString("source_locator")),
                rs.getString("source_version"),
                rs.getString("content_hash"),
                rs.getString("usage_reason"),
                rs.getObject("created_at", OffsetDateTime.class)
            ))
            .list();
        return new InvestigationModels.KnowledgeContextView(
            runId, retrieval.id(), retrieval.indexVersion(), retrieval.contextHash(),
            queryPlanView(retrieval.queryPlan()), citations
        );
    }

    public List<InvestigationModels.NextEvidenceView> nextEvidence(String runId) {
        get(runId);
        return loadNextEvidence(runId);
    }

    private List<InvestigationModels.NextEvidenceView> loadNextEvidence(String runId) {
        return jdbc.sql("""
                select id, recommendation_id, evidence_type, title, expected_predicate,
                       reason, discriminates::text,
                       estimated_impact, acquisition_cost, priority_score, status, source, created_at
                from evidence_gaps
                where investigation_run_id = :runId
                order by priority_score desc, id
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new InvestigationModels.NextEvidenceView(
                rs.getString("id"),
                rs.getString("recommendation_id"),
                rs.getString("evidence_type"),
                rs.getString("title"),
                rs.getString("expected_predicate"),
                rs.getString("reason"),
                stringList(json.read(rs.getString("discriminates"))),
                rs.getString("estimated_impact"),
                rs.getString("acquisition_cost"),
                rs.getDouble("priority_score"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getObject("created_at", OffsetDateTime.class)
            ))
            .list();
    }

    public InvestigationModels.RunDiffView diff(String currentId, String baseId) {
        InvestigationModels.InvestigationRunView current = get(currentId);
        InvestigationModels.InvestigationRunView base = baseId == null || baseId.isBlank()
            ? previous(current) : get(baseId);
        if (!current.eventId().equals(base.eventId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUN_EVENT_MISMATCH", "只能比较同一事件的调查运行");
        }
        Map<String, InvestigationModels.HypothesisResultView> currentHypotheses = hypothesesByCode(current.result());
        Map<String, InvestigationModels.HypothesisResultView> baseHypotheses = hypothesesByCode(base.result());
        Set<String> codes = new LinkedHashSet<>();
        codes.addAll(baseHypotheses.keySet());
        codes.addAll(currentHypotheses.keySet());
        List<InvestigationModels.HypothesisChangeView> changes = new ArrayList<>();
        for (String code : codes) {
            InvestigationModels.HypothesisResultView before = baseHypotheses.get(code);
            InvestigationModels.HypothesisResultView after = currentHypotheses.get(code);
            changes.add(new InvestigationModels.HypothesisChangeView(
                code,
                after != null ? after.title() : before.title(),
                before == null ? null : before.score(),
                after == null ? null : after.score(),
                before == null || after == null ? null
                    : after.score() - before.score(),
                before == null ? null : before.coverage(),
                after == null ? null : after.coverage()
            ));
        }
        Set<String> currentEvidence = new LinkedHashSet<>(current.evidenceSnapshot().evidenceIds());
        Set<String> baseEvidence = new LinkedHashSet<>(base.evidenceSnapshot().evidenceIds());
        return new InvestigationModels.RunDiffView(
            base.id(), current.id(), (int) (current.eventVersion() - base.eventVersion()),
            !current.evidenceSnapshotHash().equals(base.evidenceSnapshotHash()),
            !current.knowledgeIndexVersion().equals(base.knowledgeIndexVersion()),
            List.copyOf(changes), difference(currentEvidence, baseEvidence), difference(baseEvidence, currentEvidence)
        );
    }

    private List<HypothesisResult> buildHypotheses(
        String runId,
        List<EvidenceFact> facts,
        KnowledgeModels.RetrievalRunView retrieval,
        DomainPackCatalog catalog
    ) {
        Map<String, List<ExpectedRule>> rules = rulesByHypothesis(catalog);
        List<KnowledgeModels.RetrievalHitView> selectedHits = retrieval.intents().stream()
            .flatMap(intent -> intent.hits().stream())
            .filter(KnowledgeModels.RetrievalHitView::selected)
            .collect(Collectors.collectingAndThen(
                Collectors.toMap(KnowledgeModels.RetrievalHitView::knowledgeUnitId, value -> value,
                    (left, right) -> left, LinkedHashMap::new),
                value -> List.copyOf(value.values())
            ));
        List<HypothesisResult> results = new ArrayList<>();
        int limit = catalog.manifest().path("hypothesis_limit").asInt(4);
        int count = 0;
        for (JsonNode definition : catalog.hypotheses().path("hypotheses")) {
            if (count++ >= limit) {
                break;
            }
            String code = definition.path("code").asText();
            ScoreResult score = scoring.score(rules.getOrDefault(code, List.of()), facts);
            String hypothesisId = ids.next("hyp");
            List<InvestigationModels.ExpectedEvidenceView> expectedEvidence = new ArrayList<>();
            for (ExpectedRule rule : rules.getOrDefault(code, List.of())) {
                expectedEvidence.add(new InvestigationModels.ExpectedEvidenceView(
                    rule.predicate(),
                    rule.weight(),
                    rule.relation(),
                    rule.required(),
                    "DOMAIN_RULE",
                    rule.id()
                ));
            }
            jdbc.sql("""
                    insert into hypotheses(
                        id, investigation_run_id, code, title, description, status,
                        score, score_band, evidence_coverage, generated_by, expected_evidence
                    ) values (
                        :id, :runId, :code, :title, :description, 'ACTIVE',
                        :score, :band, :coverage, 'DOMAIN_PACK', cast(:expectedEvidence as jsonb)
                    )
                    """)
                .param("id", hypothesisId)
                .param("runId", runId)
                .param("code", code)
                .param("title", definition.path("title").asText())
                .param("description", definition.path("description").asText())
                .param("score", score.score())
                .param("band", score.band())
                .param("coverage", score.coverage())
                .param("expectedEvidence", json.write(expectedEvidence))
                .update();
            for (Contribution contribution : score.contributions()) {
                jdbc.sql("""
                        insert into hypothesis_evidence(
                            hypothesis_id, evidence_id, observation_id, relation,
                            rule_weight, source_reliability, extraction_confidence, relevance,
                            contribution, reason, rule_id, rule_version
                        ) values (
                            :hypothesisId, :evidenceId, :observationId, :relation,
                            :ruleWeight, :sourceReliability, :extractionConfidence, :relevance,
                            :contribution, :reason, :ruleId, :ruleVersion
                        )
                        """)
                    .param("hypothesisId", hypothesisId)
                    .param("evidenceId", contribution.evidenceId())
                    .param("observationId", contribution.observationId())
                    .param("relation", contribution.relation())
                    .param("ruleWeight", contribution.ruleWeight())
                    .param("sourceReliability", contribution.sourceReliability())
                    .param("extractionConfidence", contribution.extractionConfidence())
                    .param("relevance", contribution.relevance())
                    .param("contribution", contribution.value())
                    .param("reason", contribution.reason())
                    .param("ruleId", contribution.ruleId())
                    .param("ruleVersion", contribution.ruleVersion())
                    .update();
            }
            List<String> citationIds = createCitations(
                runId, hypothesisId, code, selectedHits, catalog
            );
            String groundingStatus = citationIds.isEmpty() ? "LIMITED" : "GROUNDED";
            List<String> knowledgeLimitations = citationIds.isEmpty()
                ? List.of("当前检索快照中没有与该假设规则 Predicate 匹配的知识单元。")
                : List.of();
            results.add(new HypothesisResult(
                hypothesisId, code, definition.path("title").asText(),
                definition.path("description").asText(), score,
                List.copyOf(expectedEvidence), citationIds,
                groundingStatus, knowledgeLimitations
            ));
        }
        results.sort(Comparator.comparingInt((HypothesisResult value) -> value.score().score()).reversed());
        return List.copyOf(results);
    }

    private List<String> createCitations(
        String runId,
        String hypothesisId,
        String code,
        List<KnowledgeModels.RetrievalHitView> hits,
        DomainPackCatalog catalog
    ) {
        Set<String> hypothesisPredicates = new LinkedHashSet<>();
        for (JsonNode rule : catalog.rules().path("rules")) {
            if (code.equals(rule.path("hypothesis").asText())) {
                hypothesisPredicates.add(rule.path("predicate").asText());
            }
        }
        List<KnowledgeModels.RetrievalHitView> matching = hits.stream()
            .filter(hit -> citationMatches(hypothesisPredicates, hit))
            .limit(2)
            .toList();
        List<String> idsCreated = new ArrayList<>();
        for (KnowledgeModels.RetrievalHitView hit : matching) {
            String citationId = ids.next("cit");
            jdbc.sql("""
                    insert into knowledge_citations(
                        id, investigation_run_id, knowledge_unit_id, target_type, target_id,
                        source_locator, source_version, content_hash, usage_reason
                    ) values (
                        :id, :runId, :unitId, 'HYPOTHESIS', :targetId,
                        cast(:sourceLocator as jsonb), :sourceVersion, :contentHash, :usageReason
                    )
                    """)
                .param("id", citationId)
                .param("runId", runId)
                .param("unitId", hit.knowledgeUnitId())
                .param("targetId", hypothesisId)
                .param("sourceLocator", json.write(hit.sourceLocator()))
                .param("sourceVersion", hit.sourceVersion())
                .param("contentHash", hit.contentHash())
                .param("usageReason", "为当前原因假设提供与规则 Predicate 对应的可回溯知识背景；不产生评分贡献")
                .update();
            idsCreated.add(citationId);
        }
        return List.copyOf(idsCreated);
    }

    private List<GapResult> buildGaps(
        String runId,
        List<HypothesisResult> hypotheses,
        List<EvidenceFact> facts,
        DomainPackCatalog catalog
    ) {
        Set<String> present = facts.stream().filter(EvidenceFact::present)
            .map(EvidenceFact::predicate).collect(Collectors.toSet());
        Set<String> topTwo = hypotheses.stream().limit(2).map(HypothesisResult::code)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Double> unresolvedWeights = catalog.rules().path("rules").findValuesAsText("predicate")
            .stream().collect(Collectors.toMap(value -> value, value -> 0.5, Math::max));
        for (JsonNode rule : catalog.rules().path("rules")) {
            unresolvedWeights.merge(rule.path("predicate").asText(), rule.path("expected_weight").asDouble(0.5), Math::max);
        }
        List<GapResult> gaps = new ArrayList<>();
        for (JsonNode recommendation : catalog.nextEvidence().path("recommendations")) {
            String predicate = recommendation.path("expected_predicate").asText();
            if (present.contains(predicate)) {
                continue;
            }
            List<String> discriminates = new ArrayList<>();
            recommendation.path("discriminates").forEach(value -> discriminates.add(value.asText()));
            boolean separatesTop = discriminates.containsAll(topTwo) && topTwo.size() == 2;
            String impact = recommendation.path("estimated_impact").asText("medium");
            String cost = recommendation.path("acquisition_cost").asText("medium");
            double unresolved = unresolvedWeights.getOrDefault(predicate, 0.5);
            double priority = unresolved * (separatesTop ? 1.0 : 0.6) * level(impact)
                * availability(cost) / cost(cost);
            String id = ids.next("gap");
            String reason = separatesTop
                ? "可区分当前排名前两位假设：" + String.join("、", topTwo)
                : "补足未覆盖的关键 Observation：" + predicate;
            jdbc.sql("""
                    insert into evidence_gaps(
                        id, investigation_run_id, recommendation_id, evidence_type, title,
                        expected_predicate, reason, discriminates,
                        estimated_impact, acquisition_cost, priority_score, status, source
                    ) values (
                        :id, :runId, :recommendationId, 'OBSERVATION', :title,
                        :expectedPredicate, :reason, cast(:discriminates as jsonb),
                        :impact, :cost, :priority, 'OPEN', 'DOMAIN_RULE'
                    )
                    """)
                .param("id", id)
                .param("runId", runId)
                .param("recommendationId", recommendation.path("id").asText())
                .param("title", recommendation.path("title").asText())
                .param("expectedPredicate", predicate)
                .param("reason", reason)
                .param("discriminates", json.write(discriminates))
                .param("impact", impact.toUpperCase(Locale.ROOT))
                .param("cost", cost.toUpperCase(Locale.ROOT))
                .param("priority", priority)
                .update();
            gaps.add(new GapResult(
                id, recommendation.path("id").asText(), recommendation.path("title").asText(),
                predicate, reason, List.copyOf(discriminates), impact, cost, priority
            ));
        }
        return gaps.stream().sorted(Comparator.comparingDouble(GapResult::priority).reversed()).toList();
    }

    private InvestigationModels.InvestigationResultView resultSnapshot(
        EventModels.EventDetail event,
        EvidenceSnapshot evidenceSnapshot,
        KnowledgeModels.RetrievalRunView retrieval,
        List<HypothesisResult> hypotheses,
        List<InvestigationModels.NextEvidenceView> gaps
    ) {
        List<InvestigationModels.HypothesisResultView> hypothesisViews = hypotheses.stream()
            .map(hypothesis -> new InvestigationModels.HypothesisResultView(
                hypothesis.id(),
                hypothesis.code(),
                hypothesis.title(),
                hypothesis.description(),
                hypothesis.score().score(),
                hypothesis.score().band(),
                hypothesis.score().coverage(),
                hypothesis.score().positive(),
                hypothesis.score().negative(),
                hypothesis.score().missingPenalty(),
                hypothesis.expectedEvidence(),
                hypothesis.score().contributions().stream()
                    .map(this::contributionView)
                    .toList(),
                hypothesis.citationIds(),
                hypothesis.groundingStatus(),
                hypothesis.knowledgeLimitations()
            ))
            .toList();
        return new InvestigationModels.InvestigationResultView(
            "支持指数不是概率，只表示当前证据与规则下的相对支持程度",
            "QUERY_PLAN -> KNOWLEDGE_CONTEXT -> GROUNDED_HYPOTHESIS -> EXPECTED_EVIDENCE -> EVIDENCE_RELATION -> SCORE_COVERAGE -> GAP -> NEXT_EVIDENCE",
            PLANNER_VERSION,
            event.version(),
            evidenceSnapshot.hash(),
            retrieval.indexVersion(),
            retrieval.id(),
            retrieval.contextHash(),
            evidenceSnapshot.view(),
            List.copyOf(hypothesisViews),
            List.copyOf(gaps)
        );
    }

    private InvestigationModels.ContributionView contributionView(Contribution contribution) {
        return new InvestigationModels.ContributionView(
            contribution.ruleId(),
            contribution.ruleVersion(),
            contribution.predicate(),
            contribution.relation(),
            contribution.ruleWeight(),
            contribution.evidenceId(),
            contribution.observationId(),
            contribution.sourceReliability(),
            contribution.extractionConfidence(),
            contribution.relevance(),
            contribution.value(),
            contribution.reason()
        );
    }

    private List<QueryIntent> buildQueryPlan(EventModels.EventDetail event, List<EvidenceFact> facts) {
        DomainPackDefinition definition = domainPacks.require(event.domainPackKey());
        DomainPackCatalog catalog = definition.content();
        List<String> predicateCodes = facts.stream()
            .filter(EvidenceFact::present)
            .map(EvidenceFact::predicate)
            .distinct()
            .toList();
        String predicateLabels = predicateCodes.stream()
            .map(code -> catalog.vocabulary().path("predicates").path(code).path("label").asText(code))
            .collect(Collectors.joining(" "));
        Map<String, String> values = Map.of(
            "title", event.title(),
            "description", defaultText(event.description(), ""),
            "event_type", event.eventType(),
            "subject_label", event.eventIr().path("subjects").path(0).path("label").asText(""),
            "predicates", String.join(" ", predicateCodes),
            "predicate_labels", predicateLabels
        );
        List<QueryIntent> result = new ArrayList<>();
        for (JsonNode intent : catalog.retrievalConfig().path("query_intents")) {
            String query = intent.path("template").asText();
            for (Map.Entry<String, String> value : values.entrySet()) {
                query = query.replace("{" + value.getKey() + "}", value.getValue());
            }
            result.add(new QueryIntent(
                intent.path("type").asText(),
                query.replaceAll("\\s+", " ").trim()
            ));
        }
        return List.copyOf(result);
    }

    private EvidenceSnapshot evidenceSnapshot(String eventId) {
        List<SnapshotRow> rows = jdbc.sql("""
                select e.id evidence_id, e.type evidence_type, e.source evidence_source,
                       e.status evidence_status, e.original_name, e.content_type,
                       e.checksum_sha256, e.generation evidence_generation,
                       e.reliability, e.created_at evidence_created_at,
                       o.id observation_id, o.generation observation_generation,
                       o.version observation_version, o.predicate, o.value::text observation_value,
                       o.description observation_description, o.model_confidence,
                       o.verification_status
                from evidence e
                left join observations o
                  on o.evidence_id = e.id and o.generation = e.generation
                where e.event_id = :eventId and e.workspace_id = :workspaceId
                order by e.id, o.id
                """)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> new SnapshotRow(
                rs.getString("evidence_id"),
                rs.getString("evidence_type"),
                rs.getString("evidence_source"),
                rs.getString("evidence_status"),
                rs.getString("original_name"),
                rs.getString("content_type"),
                rs.getString("checksum_sha256"),
                rs.getInt("evidence_generation"),
                rs.getDouble("reliability"),
                rs.getObject("evidence_created_at", OffsetDateTime.class),
                rs.getString("observation_id"),
                rs.getObject("observation_generation") == null ? null : rs.getInt("observation_generation"),
                rs.getObject("observation_version") == null ? null : rs.getLong("observation_version"),
                rs.getString("predicate"),
                rs.getString("observation_value") == null ? null : json.read(rs.getString("observation_value")),
                rs.getString("observation_description"),
                rs.getObject("model_confidence") == null ? null : rs.getDouble("model_confidence"),
                rs.getString("verification_status")
            ))
            .list();

        Map<String, List<SnapshotRow>> grouped = rows.stream()
            .collect(Collectors.groupingBy(
                SnapshotRow::evidenceId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        List<InvestigationModels.EvidenceSnapshotItemView> evidence = new ArrayList<>();
        List<EvidenceFact> facts = new ArrayList<>();
        for (List<SnapshotRow> group : grouped.values()) {
            SnapshotRow base = group.getFirst();
            List<InvestigationModels.ObservationSnapshotView> observations = new ArrayList<>();
            for (SnapshotRow row : group) {
                if (row.observationId() == null) {
                    continue;
                }
                boolean valuePresent = present(row.observationValue());
                observations.add(new InvestigationModels.ObservationSnapshotView(
                    row.observationId(),
                    row.observationGeneration(),
                    row.observationVersion(),
                    row.predicate(),
                    row.observationValue(),
                    row.observationDescription(),
                    row.modelConfidence(),
                    row.verificationStatus(),
                    valuePresent
                ));
                if (Set.of("CONFIRMED", "VERIFIED").contains(row.verificationStatus())) {
                    facts.add(new EvidenceFact(
                        row.evidenceId(),
                        row.observationId(),
                        row.predicate(),
                        valuePresent,
                        row.reliability(),
                        row.modelConfidence(),
                        1.0
                    ));
                }
            }
            evidence.add(new InvestigationModels.EvidenceSnapshotItemView(
                base.evidenceId(),
                base.evidenceType(),
                base.evidenceSource(),
                base.evidenceStatus(),
                base.originalName(),
                base.contentType(),
                base.checksumSha256(),
                base.evidenceGeneration(),
                base.reliability(),
                base.evidenceCreatedAt(),
                List.copyOf(observations)
            ));
        }
        List<String> evidenceIds = evidence.stream()
            .map(InvestigationModels.EvidenceSnapshotItemView::id)
            .toList();
        InvestigationModels.EvidenceSnapshotView view = new InvestigationModels.EvidenceSnapshotView(
            EVIDENCE_SNAPSHOT_SCHEMA_VERSION,
            List.copyOf(evidenceIds),
            List.copyOf(evidence)
        );
        return new EvidenceSnapshot(
            Hashing.sha256(json.canonicalWrite(view)),
            view,
            List.copyOf(facts)
        );
    }

    private Map<String, List<ExpectedRule>> rulesByHypothesis(DomainPackCatalog catalog) {
        Map<String, List<ExpectedRule>> values = new LinkedHashMap<>();
        for (JsonNode rule : catalog.rules().path("rules")) {
            String hypothesis = rule.path("hypothesis").asText();
            values.computeIfAbsent(hypothesis, ignored -> new ArrayList<>()).add(new ExpectedRule(
                rule.path("id").asText(), rule.path("version").asText("1"), hypothesis,
                rule.path("predicate").asText(), rule.path("relation").asText(),
                rule.path("expected_weight").asDouble(), rule.path("required").asBoolean(false)
            ));
        }
        return values;
    }

    private int nextSequence(String eventId) {
        Integer value = jdbc.sql("""
                select coalesce(max(sequence_no), 0) + 1 from investigation_runs where event_id = :eventId
                """)
            .param("eventId", eventId)
            .query(Integer.class)
            .single();
        return value;
    }

    private InvestigationModels.InvestigationRunView previous(InvestigationModels.InvestigationRunView current) {
        return jdbc.sql("""
                select * from investigation_runs
                where event_id = :eventId and sequence_no < :sequence and workspace_id = :workspaceId
                order by sequence_no desc limit 1
                """)
            .param("eventId", current.eventId())
            .param("sequence", current.sequenceNo())
            .param("workspaceId", InstanceScope.ID)
            .query(this::mapRun)
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PREVIOUS_RUN_NOT_FOUND", "没有可比较的上一轮调查"));
    }

    private InvestigationModels.InvestigationRunView mapRun(ResultSet rs, int rowNum) throws SQLException {
        String result = rs.getString("result_snapshot");
        int snapshotVersion = rs.getInt("evidence_snapshot_schema_version");
        JsonNode resultNode = result == null ? mapper.createObjectNode() : json.read(result);
        String snapshotJson = rs.getString("evidence_snapshot");
        InvestigationModels.EvidenceSnapshotView evidenceSnapshot;
        if (snapshotJson != null) {
            evidenceSnapshot = mapper.convertValue(
                json.read(snapshotJson),
                InvestigationModels.EvidenceSnapshotView.class
            );
        } else {
            evidenceSnapshot = new InvestigationModels.EvidenceSnapshotView(
                snapshotVersion,
                stringList(resultNode.path("evidence_snapshot").path("evidence_ids")),
                List.of()
            );
        }
        InvestigationModels.InvestigationResultView resultView = resultView(
            resultNode,
            evidenceSnapshot,
            rs.getLong("event_version"),
            rs.getString("evidence_snapshot_hash"),
            rs.getString("knowledge_index_version"),
            rs.getString("retrieval_run_id")
        );
        OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
        boolean stale = completedAt != null && jdbc.sql("""
                select exists(
                    select 1 from events e where e.id = :eventId and e.version > :eventVersion
                    union all
                    select 1 from evidence ev where ev.event_id = :eventId and ev.created_at > :completedAt
                )
                """)
            .param("eventId", rs.getString("event_id"))
            .param("eventVersion", rs.getLong("event_version"))
            .param("completedAt", completedAt)
            .query(Boolean.class)
            .single();
        return new InvestigationModels.InvestigationRunView(
            rs.getString("id"), rs.getString("event_id"), rs.getInt("sequence_no"),
            rs.getString("status"), rs.getLong("event_version"), snapshotVersion,
            rs.getString("evidence_snapshot_hash"), evidenceSnapshot,
            rs.getString("model_policy_version"), rs.getString("rule_pack_version"),
            rs.getString("domain_pack_key"), rs.getString("domain_pack_version"),
            rs.getString("domain_pack_fingerprint"),
            rs.getString("knowledge_index_version"), rs.getString("retrieval_run_id"),
            json.read(rs.getString("event_ir_snapshot")), resultView,
            stale, rs.getObject("started_at", OffsetDateTime.class), completedAt,
            rs.getString("error_code"), rs.getString("error_message"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private InvestigationModels.InvestigationResultView resultView(
        JsonNode source,
        InvestigationModels.EvidenceSnapshotView evidenceSnapshot,
        long eventVersion,
        String evidenceHash,
        String knowledgeIndexVersion,
        String retrievalRunId
    ) {
        if (source == null || source.isEmpty()) {
            return new InvestigationModels.InvestigationResultView(
                "支持指数不是概率，只表示当前证据与规则下的相对支持程度",
                "",
                PLANNER_VERSION,
                eventVersion,
                evidenceHash,
                knowledgeIndexVersion,
                retrievalRunId,
                "",
                evidenceSnapshot,
                List.of(),
                List.of()
            );
        }
        ObjectNode normalized = source.deepCopy();
        normalized.set("evidence_snapshot", mapper.valueToTree(evidenceSnapshot));
        return mapper.convertValue(normalized, InvestigationModels.InvestigationResultView.class);
    }

    private static boolean citationMatches(
        Set<String> hypothesisPredicates,
        KnowledgeModels.RetrievalHitView hit
    ) {
        for (String predicate : hit.expectedPredicates()) {
            if (hypothesisPredicates.contains(predicate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean present(JsonNode value) {
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble() != 0;
        }
        if (value.isTextual()) {
            return !value.asText().isBlank();
        }
        return !value.isNull() && !value.isMissingNode();
    }

    private static Map<String, InvestigationModels.HypothesisResultView> hypothesesByCode(
        InvestigationModels.InvestigationResultView result
    ) {
        return result.hypotheses().stream().collect(Collectors.toMap(
            InvestigationModels.HypothesisResultView::code,
            value -> value,
            (left, right) -> left,
            LinkedHashMap::new
        ));
    }

    private static List<String> stringList(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static InvestigationModels.QueryPlanView queryPlanView(JsonNode value) {
        List<InvestigationModels.QueryIntentView> intents = new ArrayList<>();
        value.path("intents").forEach(intent -> intents.add(new InvestigationModels.QueryIntentView(
            intent.path("type").asText(),
            intent.path("query").asText()
        )));
        return new InvestigationModels.QueryPlanView(
            value.path("domain_pack_key").asText(),
            value.path("planner_version").asText(),
            value.path("keyword_top_k").asInt(),
            value.path("vector_top_k").asInt(),
            value.path("final_top_k").asInt(),
            List.copyOf(intents)
        );
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(value -> !right.contains(value)).toList();
    }

    private static double level(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "high" -> 1.0;
            case "low" -> 0.45;
            default -> 0.7;
        };
    }

    private static double availability(String cost) {
        return switch (cost.toLowerCase(Locale.ROOT)) {
            case "low" -> 0.9;
            case "high" -> 0.5;
            default -> 0.7;
        };
    }

    private static double cost(String cost) {
        return switch (cost.toLowerCase(Locale.ROOT)) {
            case "low" -> 1.0;
            case "high" -> 3.0;
            default -> 2.0;
        };
    }

    private static String defaultText(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private record EvidenceSnapshot(
        String hash,
        InvestigationModels.EvidenceSnapshotView view,
        List<EvidenceFact> facts
    ) {}

    private record SnapshotRow(
        String evidenceId,
        String evidenceType,
        String evidenceSource,
        String evidenceStatus,
        String originalName,
        String contentType,
        String checksumSha256,
        int evidenceGeneration,
        double reliability,
        OffsetDateTime evidenceCreatedAt,
        String observationId,
        Integer observationGeneration,
        Long observationVersion,
        String predicate,
        JsonNode observationValue,
        String observationDescription,
        Double modelConfidence,
        String verificationStatus
    ) {}

    private record BeginRun(
        EventModels.EventDetail event,
        EvidenceSnapshot evidenceSnapshot,
        DomainPackDefinition domainPack,
        String packKey,
        String runId,
        String endpoint,
        String idempotencyKey,
        String replayRunId
    ) {
        static BeginRun created(
            EventModels.EventDetail event,
            EvidenceSnapshot evidenceSnapshot,
            DomainPackDefinition domainPack,
            String packKey,
            String runId,
            String endpoint,
            String idempotencyKey
        ) {
            return new BeginRun(
                event, evidenceSnapshot, domainPack, packKey, runId, endpoint, idempotencyKey, null
            );
        }

        static BeginRun replay(String runId) {
            return new BeginRun(null, null, null, null, null, null, null, runId);
        }
    }

    private record HypothesisResult(
        String id,
        String code,
        String title,
        String description,
        ScoreResult score,
        List<InvestigationModels.ExpectedEvidenceView> expectedEvidence,
        List<String> citationIds,
        String groundingStatus,
        List<String> knowledgeLimitations
    ) {}
    private record GapResult(
        String id,
        String recommendationId,
        String title,
        String expectedPredicate,
        String reason,
        List<String> discriminates,
        String estimatedImpact,
        String acquisitionCost,
        double priority
    ) {}
}
