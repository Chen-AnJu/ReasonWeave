package dev.reasonweave.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reasonweave.domainpack.DomainPackCatalog;
import dev.reasonweave.domainpack.DomainPackDefinition;
import dev.reasonweave.domainpack.DomainPackRegistry;
import dev.reasonweave.model.ModelGateway;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.Hashing;
import dev.reasonweave.shared.JsonSupport;
import dev.reasonweave.shared.ids.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeService {
    private static final String SEGMENTER_VERSION = "markdown-heading-v1";
    private static final Pattern KEYWORD_TERM = Pattern.compile("[\\p{L}\\p{N}_./:-]+");
    private static final int MAX_KEYWORD_TERMS = 64;
    private static final OffsetDateTime USAGE_CURSOR_START = OffsetDateTime.parse("9999-12-31T23:59:59Z");
    private static final String USAGE_CURSOR_KEY_START = "\uffff";
    private static final int UNIT_DETAIL_USAGE_LIMIT = 20;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final JsonSupport json;
    private final ObjectMapper mapper;
    private final DomainPackRegistry domainPacks;
    private final ModelGateway model;
    private final TransactionTemplate transaction;

    public KnowledgeService(
        JdbcClient jdbc,
        IdGenerator ids,
        JsonSupport json,
        ObjectMapper mapper,
        DomainPackRegistry domainPacks,
        ModelGateway model,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
        this.mapper = mapper;
        this.domainPacks = domainPacks;
        this.model = model;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<KnowledgeModels.SourceView> listSources() {
        return jdbc.sql("""
                select s.*,
                       (select count(*) from knowledge_documents d where d.knowledge_source_id = s.id) document_count,
                       (select count(*) from knowledge_units u where u.knowledge_source_id = s.id) unit_count
                from knowledge_sources s
                where s.workspace_id = :workspaceId
                order by s.created_at desc
                """)
            .param("workspaceId", InstanceScope.ID)
            .query(this::mapSource)
            .list();
    }

    public List<KnowledgeModels.DocumentView> listDocuments(String sourceId) {
        String filter = sourceId == null ? "" : sourceId;
        return jdbc.sql("""
                select d.*,
                       (select count(*) from knowledge_units u where u.document_id = d.id) unit_count
                from knowledge_documents d
                where d.workspace_id = :workspaceId
                  and (:sourceId = '' or d.knowledge_source_id = :sourceId)
                order by d.created_at desc
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("sourceId", filter)
            .query(this::mapDocument)
            .list();
    }

    public KnowledgeModels.SourceDetailView getSourceDetail(String id) {
        KnowledgeModels.SourceView value = source(id);
        List<KnowledgeModels.DocumentView> documents = listDocuments(id);
        Map<String, Object> usage = jdbc.sql("""
                select
                    count(distinct u.id) filter (where u.status = 'PUBLISHED') published_unit_count,
                    count(distinct u.id) filter (where u.embedding is not null) embedding_unit_count,
                    count(distinct c.id) citation_count,
                    count(distinct (rh.retrieval_run_id, rh.query_intent)) retrieval_usage_count
                from knowledge_units u
                left join knowledge_citations c on c.knowledge_unit_id = u.id
                left join retrieval_hits rh on rh.knowledge_unit_id = u.id
                where u.knowledge_source_id = :sourceId and u.workspace_id = :workspaceId
                """)
            .param("sourceId", id)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> Map.<String, Object>of(
                "published", rs.getInt("published_unit_count"),
                "embedded", rs.getInt("embedding_unit_count"),
                "citations", rs.getInt("citation_count"),
                "retrievals", rs.getInt("retrieval_usage_count")
            ))
            .single();
        return new KnowledgeModels.SourceDetailView(
            value,
            documents,
            (Integer) usage.get("published"),
            (Integer) usage.get("embedded"),
            (Integer) usage.get("citations"),
            (Integer) usage.get("retrievals"),
            indexVersion(value.domainPackKey()),
            embeddingProvenance(value)
        );
    }

    public KnowledgeModels.UnitPageView listUnits(String sourceId, String cursor, int requestedLimit) {
        source(sourceId);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        String cursorId = decodeUnitCursor(cursor);
        List<KnowledgeModels.UnitSummaryView> rows = jdbc.sql("""
                select id, knowledge_source_id, document_id, topic, title,
                       expected_predicates::text, source_locator::text, source_version,
                       content_hash, status, embedding is not null embedding_present, created_at
                from knowledge_units
                where workspace_id = :workspaceId and knowledge_source_id = :sourceId
                  and id > :cursorId
                order by id
                limit :limit
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("sourceId", sourceId)
            .param("cursorId", cursorId)
            .param("limit", limit + 1)
            .query((rs, rowNum) -> new KnowledgeModels.UnitSummaryView(
                rs.getString("id"), rs.getString("knowledge_source_id"),
                rs.getString("document_id"), rs.getString("topic"), rs.getString("title"),
                json.read(rs.getString("expected_predicates")), json.read(rs.getString("source_locator")),
                rs.getString("source_version"), rs.getString("content_hash"), rs.getString("status"),
                rs.getBoolean("embedding_present"), rs.getObject("created_at", OffsetDateTime.class)
            ))
            .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            nextCursor = encodeUnitCursor(rows.get(limit - 1).id());
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new KnowledgeModels.UnitPageView(List.copyOf(rows), nextCursor, limit);
    }

    public KnowledgeModels.UnitDetailView getUnitDetail(String id) {
        Map<String, Object> unit = jdbc.sql("""
                select id, knowledge_source_id, document_id, domain_pack_key, topic, title,
                       content, applicability::text, expected_predicates::text,
                       source_locator::text, source_version, content_hash, status,
                       embedding is not null embedding_present, created_at
                from knowledge_units
                where id = :id and workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", rs.getString("id"));
                value.put("source_id", rs.getString("knowledge_source_id"));
                value.put("document_id", rs.getString("document_id"));
                value.put("domain_pack_key", rs.getString("domain_pack_key"));
                value.put("topic", rs.getString("topic"));
                value.put("title", rs.getString("title"));
                value.put("content", rs.getString("content"));
                value.put("applicability", json.read(rs.getString("applicability")));
                value.put("expected_predicates", json.read(rs.getString("expected_predicates")));
                value.put("source_locator", json.read(rs.getString("source_locator")));
                value.put("source_version", rs.getString("source_version"));
                value.put("content_hash", rs.getString("content_hash"));
                value.put("status", rs.getString("status"));
                value.put("embedding_present", rs.getBoolean("embedding_present"));
                value.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
                return value;
            })
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "知识单元不存在"));

        KnowledgeModels.CitationUsagePageView citations = citationUsagePage(
            id, null, UNIT_DETAIL_USAGE_LIMIT
        );
        KnowledgeModels.RetrievalUsagePageView retrievals = retrievalUsagePage(
            id, null, UNIT_DETAIL_USAGE_LIMIT
        );

        String sourceId = unit.get("source_id").toString();
        String documentId = unit.get("document_id").toString();
        return new KnowledgeModels.UnitDetailView(
            id,
            source(sourceId),
            document(documentId),
            unit.get("domain_pack_key").toString(),
            (String) unit.get("topic"),
            unit.get("title").toString(),
            unit.get("content").toString(),
            (JsonNode) unit.get("applicability"),
            (JsonNode) unit.get("expected_predicates"),
            (JsonNode) unit.get("source_locator"),
            unit.get("source_version").toString(),
            unit.get("content_hash").toString(),
            unit.get("status").toString(),
            (Boolean) unit.get("embedding_present"),
            embeddingProvenance(source(sourceId)),
            citations.items(),
            citations.total(),
            citations.nextCursor(),
            retrievals.items(),
            retrievals.total(),
            retrievals.nextCursor(),
            (OffsetDateTime) unit.get("created_at")
        );
    }

    public KnowledgeModels.CitationUsagePageView listCitationUsages(
        String unitId,
        String cursor,
        int requestedLimit
    ) {
        assertUnit(unitId);
        return citationUsagePage(unitId, cursor, requestedLimit);
    }

    public KnowledgeModels.RetrievalUsagePageView listRetrievalUsages(
        String unitId,
        String cursor,
        int requestedLimit
    ) {
        assertUnit(unitId);
        return retrievalUsagePage(unitId, cursor, requestedLimit);
    }

    private KnowledgeModels.CitationUsagePageView citationUsagePage(
        String unitId,
        String cursor,
        int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        UsageCursor decoded = decodeUsageCursor(cursor, "知识引用游标无效");
        List<KnowledgeModels.CitationUsageView> rows = jdbc.sql("""
                select c.id, c.investigation_run_id, ir.event_id, c.target_type, c.target_id,
                       h.code target_code, h.title target_title, c.source_locator::text,
                       c.source_version, c.content_hash, c.usage_reason, c.created_at
                from knowledge_citations c
                join investigation_runs ir on ir.id = c.investigation_run_id
                left join hypotheses h on h.id = c.target_id
                    and h.investigation_run_id = c.investigation_run_id
                    and c.target_type = 'HYPOTHESIS'
                where c.knowledge_unit_id = :unitId and ir.workspace_id = :workspaceId
                  and (c.created_at, c.id) < (:cursorAt, :cursorKey)
                order by c.created_at desc, c.id desc
                limit :limit
                """)
            .param("unitId", unitId)
            .param("workspaceId", InstanceScope.ID)
            .param("cursorAt", decoded.createdAt())
            .param("cursorKey", decoded.key())
            .param("limit", limit + 1)
            .query((rs, rowNum) -> new KnowledgeModels.CitationUsageView(
                rs.getString("id"), rs.getString("investigation_run_id"), rs.getString("event_id"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("target_code"),
                rs.getString("target_title"), json.read(rs.getString("source_locator")),
                rs.getString("source_version"), rs.getString("content_hash"),
                rs.getString("usage_reason"), rs.getObject("created_at", OffsetDateTime.class)
            ))
            .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            KnowledgeModels.CitationUsageView boundary = rows.get(limit - 1);
            nextCursor = encodeUsageCursor(boundary.createdAt(), boundary.citationId());
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        long total = jdbc.sql("""
                select count(*) from knowledge_citations c
                join investigation_runs ir on ir.id = c.investigation_run_id
                where c.knowledge_unit_id = :unitId and ir.workspace_id = :workspaceId
                """)
            .param("unitId", unitId)
            .param("workspaceId", InstanceScope.ID)
            .query(Long.class)
            .single();
        return new KnowledgeModels.CitationUsagePageView(List.copyOf(rows), nextCursor, limit, total);
    }

    private KnowledgeModels.RetrievalUsagePageView retrievalUsagePage(
        String unitId,
        String cursor,
        int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        UsageCursor decoded = decodeUsageCursor(cursor, "检索使用游标无效");
        List<KnowledgeModels.RetrievalUsageView> rows = jdbc.sql("""
                select rh.retrieval_run_id, rr.investigation_run_id, rh.query_intent,
                       rh.keyword_rank, rh.vector_rank, rh.fusion_rank, rh.fusion_score,
                       rh.selected, rh.selection_reason, rr.index_version,
                       rr.embedding_model, rr.created_at
                from retrieval_hits rh
                join retrieval_runs rr on rr.id = rh.retrieval_run_id
                where rh.knowledge_unit_id = :unitId and rr.workspace_id = :workspaceId
                  and (rr.created_at, rh.retrieval_run_id || ':' || rh.query_intent)
                      < (:cursorAt, :cursorKey)
                order by rr.created_at desc, rh.retrieval_run_id desc, rh.query_intent desc
                limit :limit
                """)
            .param("unitId", unitId)
            .param("workspaceId", InstanceScope.ID)
            .param("cursorAt", decoded.createdAt())
            .param("cursorKey", decoded.key())
            .param("limit", limit + 1)
            .query((rs, rowNum) -> {
                Number keywordRank = (Number) rs.getObject("keyword_rank");
                Number vectorRank = (Number) rs.getObject("vector_rank");
                return new KnowledgeModels.RetrievalUsageView(
                    rs.getString("retrieval_run_id"), rs.getString("investigation_run_id"),
                    rs.getString("query_intent"), keywordRank == null ? null : keywordRank.intValue(),
                    vectorRank == null ? null : vectorRank.intValue(), rs.getInt("fusion_rank"),
                    rs.getDouble("fusion_score"), rs.getBoolean("selected"),
                    rs.getString("selection_reason"), rs.getString("index_version"),
                    rs.getString("embedding_model"), rs.getObject("created_at", OffsetDateTime.class)
                );
            })
            .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            KnowledgeModels.RetrievalUsageView boundary = rows.get(limit - 1);
            nextCursor = encodeUsageCursor(
                boundary.createdAt(), boundary.retrievalRunId() + ":" + boundary.queryIntent()
            );
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        long total = jdbc.sql("""
                select count(*) from retrieval_hits rh
                join retrieval_runs rr on rr.id = rh.retrieval_run_id
                where rh.knowledge_unit_id = :unitId and rr.workspace_id = :workspaceId
                """)
            .param("unitId", unitId)
            .param("workspaceId", InstanceScope.ID)
            .query(Long.class)
            .single();
        return new KnowledgeModels.RetrievalUsagePageView(List.copyOf(rows), nextCursor, limit, total);
    }

    @Transactional
    public List<String> importInstalledDomainPacks() {
        List<String> imported = new ArrayList<>();
        for (DomainPackDefinition definition : domainPacks.all()) {
            imported.add(importDomainPack(definition));
        }
        return List.copyOf(imported);
    }

    private String importDomainPack(DomainPackDefinition definition) {
        DomainPackCatalog catalog = definition.content();
        JsonNode manifest = catalog.manifest();
        String domainPackKey = definition.scopedKey();
        String packFingerprint = definition.fingerprint();
        String indexProfileFingerprint = indexProfileFingerprint(definition);
        SourceImportState sourceState = jdbc.sql("""
                select id, pack_fingerprint, index_profile_fingerprint from knowledge_sources
                where workspace_id = :workspaceId and domain_pack_key = :domainPackKey
                  and name = :name and version = :version
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("domainPackKey", domainPackKey)
            .param("name", manifest.path("name").asText())
            .param("version", manifest.path("version").asText())
            .query((rs, rowNum) -> new SourceImportState(
                rs.getString("id"), rs.getString("pack_fingerprint"),
                rs.getString("index_profile_fingerprint")
            ))
            .optional()
            .orElse(null);
        String sourceId = sourceState == null ? null : sourceState.id();

        if (sourceId == null) {
            sourceId = ids.next("ks");
            jdbc.sql("""
                    insert into knowledge_sources(
                        id, workspace_id, domain_pack_key, name, source_type, version, license,
                        status, fixture_only, production_allowed, pack_fingerprint,
                        embedding_provider, embedding_model, embedding_dimension,
                        embedding_model_digest, embedding_query_instruction,
                        index_profile_fingerprint, published_at
                    ) values (
                        :id, :workspaceId, :domainPackKey, :name, 'DOMAIN_PACK', :version, :license,
                        'PUBLISHED', :fixtureOnly, :productionAllowed, :packFingerprint,
                        :embeddingProvider, :embeddingModel, :embeddingDimension,
                        :embeddingModelDigest, :embeddingQueryInstruction,
                        :indexProfileFingerprint, now()
                    )
                    """)
                .param("id", sourceId)
                .param("workspaceId", InstanceScope.ID)
                .param("domainPackKey", domainPackKey)
                .param("name", manifest.path("name").asText())
                .param("version", manifest.path("version").asText())
                .param("license", primaryLicense(catalog.knowledgeMetadata()))
                .param("fixtureOnly", manifest.path("fixture_only").asBoolean(true))
                .param("productionAllowed", manifest.path("production_allowed").asBoolean(false))
                .param("packFingerprint", packFingerprint)
                .param("embeddingProvider", model.embeddingProviderName())
                .param("embeddingModel", model.embeddingModel())
                .param("embeddingDimension", model.embeddingDimension())
                .param("embeddingModelDigest", model.embeddingModelDigest())
                .param("embeddingQueryInstruction", embeddingQueryInstruction(definition))
                .param("indexProfileFingerprint", indexProfileFingerprint)
                .update();
        } else {
            if (sourceState.fingerprint() != null
                && !sourceState.fingerprint().equals(packFingerprint)) {
                throw new IllegalStateException(
                    "Domain Pack content changed without a version change: " + domainPackKey
                );
            }
            jdbc.sql("""
                    update knowledge_sources
                    set license = :license,
                        pack_fingerprint = coalesce(pack_fingerprint, :packFingerprint),
                        status = case when index_profile_fingerprint is distinct from :indexProfileFingerprint
                            then 'INDEXING' else status end
                    where id = :id
                    """)
                .param("license", primaryLicense(catalog.knowledgeMetadata()))
                .param("packFingerprint", packFingerprint)
                .param("indexProfileFingerprint", indexProfileFingerprint)
                .param("id", sourceId)
                .update();
        }

        Map<String, String> aliases = goldenAliases(catalog);
        JsonNode metadata = catalog.knowledgeMetadata();
        Set<String> declaredExternalIds = new LinkedHashSet<>();
        for (JsonNode descriptor : metadata.path("documents")) {
            String externalId = descriptor.path("id").asText();
            declaredExternalIds.add(externalId);
            String language = descriptor.path("language").asText("en");
            String relativePath = "knowledge/" + descriptor.path("path").asText();
            String content = catalog.text(relativePath);
            String checksum = Hashing.sha256(content.getBytes(StandardCharsets.UTF_8));
            JsonNode applicability = descriptor.path("applicability");
            JsonNode expectedPredicates = descriptor.path("expected_predicates");
            ObjectNode documentMetadata = mapper.valueToTree(Map.of(
                "path", relativePath,
                "fixture_only", metadata.path("fixture_only").asBoolean(true),
                "provenance", "domain-pack:" + domainPackKey,
                "license", primaryLicense(metadata),
                "source_url", descriptor.path("source_url").asText(),
                "source_revision", descriptor.path("source_revision").asText(),
                "source_license", descriptor.path("source_license").asText(),
                "modified", descriptor.path("modified").asBoolean(false)
            ));
            DocumentImportState documentState = jdbc.sql("""
                    select id, checksum_sha256 from knowledge_documents
                    where knowledge_source_id = :sourceId and external_id = :externalId
                    """)
                .param("sourceId", sourceId)
                .param("externalId", externalId)
                .query((rs, rowNum) -> new DocumentImportState(
                    rs.getString("id"), rs.getString("checksum_sha256")
                ))
                .optional()
                .orElse(null);
            if (documentState == null) {
                String documentId = insertDocument(
                    sourceId, externalId, firstHeading(content), content, language,
                    documentMetadata
                );
                replaceUnits(
                    documentId, sourceId, domainPackKey, manifest.path("version").asText(),
                    firstHeading(content), content, aliases.getOrDefault(externalId, ""), externalId,
                    applicability, expectedPredicates, definition
                );
            } else {
                if (!documentState.checksum().equals(checksum)) {
                    throw new IllegalStateException(
                        "Knowledge document changed without a Domain Pack version change: " + externalId
                    );
                }
                jdbc.sql("""
                        update knowledge_documents
                        set language = :language, metadata = cast(:metadata as jsonb)
                        where id = :id
                        """)
                    .param("language", language)
                    .param("metadata", json.write(documentMetadata))
                    .param("id", documentState.id())
                    .update();
                jdbc.sql("""
                        update knowledge_units
                        set applicability = cast(:applicability as jsonb),
                            expected_predicates = cast(:expectedPredicates as jsonb)
                        where document_id = :documentId
                        """)
                    .param("applicability", json.write(applicability))
                    .param("expectedPredicates", json.write(expectedPredicates))
                    .param("documentId", documentState.id())
                    .update();
            }
        }
        List<String> indexedExternalIds = jdbc.sql("""
                select external_id from knowledge_documents
                where knowledge_source_id = :sourceId and external_id is not null
                order by external_id
                """)
            .param("sourceId", sourceId)
            .query(String.class)
            .list();
        if (!declaredExternalIds.equals(new LinkedHashSet<>(indexedExternalIds))) {
            throw new IllegalStateException(
                "Indexed knowledge documents differ from the Domain Pack manifest for " + domainPackKey
            );
        }
        if (sourceState != null
            && !indexProfileFingerprint.equals(sourceState.indexProfileFingerprint())) {
            reindexSource(sourceId, definition);
        }
        jdbc.sql("""
                update knowledge_sources
                set status = 'PUBLISHED', embedding_provider = :embeddingProvider,
                    embedding_model = :embeddingModel, embedding_dimension = :embeddingDimension,
                    embedding_model_digest = :embeddingModelDigest,
                    embedding_query_instruction = :embeddingQueryInstruction,
                    index_profile_fingerprint = :indexProfileFingerprint,
                    published_at = coalesce(published_at, now())
                where id = :id
                """)
            .param("embeddingProvider", model.embeddingProviderName())
            .param("embeddingModel", model.embeddingModel())
            .param("embeddingDimension", model.embeddingDimension())
            .param("embeddingModelDigest", model.embeddingModelDigest())
            .param("embeddingQueryInstruction", embeddingQueryInstruction(definition))
            .param("indexProfileFingerprint", indexProfileFingerprint)
            .param("id", sourceId)
            .update();
        return sourceId;
    }

    public KnowledgeModels.RetrievalRunView retrieve(
        List<QueryIntent> intents,
        String domainPackKey,
        String investigationRunId,
        String eventType,
        Set<String> observedPredicates
    ) {
        if (intents.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "至少需要一个检索意图");
        }
        DomainPackDefinition definition = domainPacks.require(domainPackKey);
        DomainPackCatalog catalog = definition.content();
        JsonNode config = catalog.retrievalConfig();
        int keywordTopK = config.path("keyword_top_k").asInt(20);
        int vectorTopK = config.path("vector_top_k").asInt(20);
        int finalTopK = config.path("final_top_k").asInt(6);
        int rrfK = config.path("fusion").path("k").asInt(60);
        double applicabilityMultiplier = config.path("weights").path("applicability").asDouble(1.25);
        double minimumScore = config.path("minimum_score").asDouble(0);
        String queryInstruction = embeddingQueryInstruction(definition);
        int maxUnitsPerDocument = config.path("source_diversity")
            .path("max_units_per_document").asInt(2);
        String normalizedEventType = validateRetrievalContext(definition, eventType, observedPredicates);
        Set<String> normalizedPredicates = observedPredicates == null
            ? Set.of()
            : Set.copyOf(observedPredicates);
        String resolvedPack = definition.scopedKey();
        String indexVersion = indexVersion(resolvedPack);
        String runId = ids.next("ret");

        ArrayNode intentNodes = mapper.createArrayNode();
        for (QueryIntent intent : intents) {
            intentNodes.add(mapper.valueToTree(Map.of("type", intent.type(), "query", intent.query())));
        }
        ObjectNode queryPlan = mapper.createObjectNode();
        queryPlan.set("intents", intentNodes);
        queryPlan.put("domain_pack_key", resolvedPack);
        queryPlan.put("planner_version", "query-planner-v2");
        queryPlan.put("keyword_query_mode", "OR_TERMS");
        queryPlan.put("keyword_top_k", keywordTopK);
        queryPlan.put("vector_top_k", vectorTopK);
        queryPlan.put("final_top_k", finalTopK);
        queryPlan.put("event_type", normalizedEventType);
        queryPlan.put("embedding_query_instruction", queryInstruction);
        queryPlan.set("observed_predicates", mapper.valueToTree(normalizedPredicates.stream().sorted().toList()));

        List<IntentCandidates> resolvedIntents = new ArrayList<>();
        for (QueryIntent intent : intents) {
            List<Candidate> candidates = hybrid(
                intent.query(), queryInstruction, resolvedPack, keywordTopK, vectorTopK, rrfK,
                applicabilityMultiplier, normalizedEventType, normalizedPredicates,
                !"disabled".equals(definition.vectorPolicy())
            );
            select(candidates, finalTopK, maxUnitsPerDocument, minimumScore);
            int rank = 0;
            for (Candidate candidate : candidates) {
                rank++;
                candidate.fusionRank = rank;
            }
            resolvedIntents.add(new IntentCandidates(intent, List.copyOf(candidates)));
        }

        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into retrieval_runs(
                        id, investigation_run_id, workspace_id, query_plan, retrieval_config,
                        index_version, embedding_provider, embedding_model,
                        embedding_model_digest, index_profile_fingerprint
                    ) values (
                        :id, :investigationRunId, :workspaceId, cast(:queryPlan as jsonb),
                        cast(:retrievalConfig as jsonb), :indexVersion, :embeddingProvider,
                        :embeddingModel, :embeddingModelDigest, :indexProfileFingerprint
                    )
                    """)
                .param("id", runId)
                .param("investigationRunId", investigationRunId)
                .param("workspaceId", InstanceScope.ID)
                .param("queryPlan", json.write(queryPlan))
                .param("retrievalConfig", json.write(config))
                .param("indexVersion", indexVersion)
                .param("embeddingProvider", model.embeddingProviderName())
                .param("embeddingModel", model.embeddingModel())
                .param("embeddingModelDigest", model.embeddingModelDigest())
                .param("indexProfileFingerprint", indexProfileFingerprint(definition))
                .update();
            for (IntentCandidates resolvedIntent : resolvedIntents) {
                for (Candidate candidate : resolvedIntent.candidates()) {
                jdbc.sql("""
                        insert into retrieval_hits(
                            retrieval_run_id, knowledge_unit_id, query_intent,
                            keyword_rank, keyword_score, vector_rank, vector_score,
                            fusion_rank, fusion_score, applicability_score,
                            applicability_reason, selected, selection_reason
                        ) values (
                            :runId, :unitId, :intent, :keywordRank, :keywordScore,
                            :vectorRank, :vectorScore, :fusionRank, :fusionScore,
                            :applicabilityScore, :applicabilityReason, :selected, :selectionReason
                        )
                        """)
                    .param("runId", runId)
                    .param("unitId", candidate.unitId)
                    .param("intent", resolvedIntent.intent().type())
                    .param("keywordRank", candidate.keywordRank)
                    .param("keywordScore", candidate.keywordScore)
                    .param("vectorRank", candidate.vectorRank)
                    .param("vectorScore", candidate.vectorScore)
                    .param("fusionRank", candidate.fusionRank)
                    .param("fusionScore", candidate.fusionScore)
                    .param("applicabilityScore", candidate.applicabilityScore)
                    .param("applicabilityReason", candidate.applicabilityReason)
                    .param("selected", candidate.selected)
                    .param("selectionReason", candidate.selectionReason)
                    .update();
                }
            }
        });
        return getRetrievalRun(runId);
    }

    public KnowledgeModels.RetrievalRunView debug(KnowledgeModels.RetrievalRequest request) {
        String intent = request.intent() == null || request.intent().isBlank()
            ? "CAUSE_CANDIDATES" : request.intent().toUpperCase(Locale.ROOT);
        Set<String> predicates = request.observedPredicates() == null
            ? Set.of()
            : new LinkedHashSet<>(request.observedPredicates());
        return retrieve(
            List.of(new QueryIntent(intent, request.query())),
            request.domainPackKey(),
            null,
            request.eventType(),
            predicates
        );
    }

    public KnowledgeModels.RetrievalRunView getRetrievalRun(String runId) {
        Map<String, Object> run = jdbc.sql("""
                select id, investigation_run_id, query_plan::text, retrieval_config::text,
                       index_version, embedding_provider, embedding_model,
                       embedding_model_digest, index_profile_fingerprint, created_at
                from retrieval_runs
                where id = :id and workspace_id = :workspaceId
                """)
            .param("id", runId)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", rs.getString("id"));
                value.put("investigation_run_id", rs.getString("investigation_run_id"));
                value.put("query_plan", json.read(rs.getString("query_plan")));
                value.put("retrieval_config", json.read(rs.getString("retrieval_config")));
                value.put("index_version", rs.getString("index_version"));
                value.put("embedding_provider", rs.getString("embedding_provider"));
                value.put("embedding_model", rs.getString("embedding_model"));
                value.put("embedding_model_digest", rs.getString("embedding_model_digest"));
                value.put("index_profile_fingerprint", rs.getString("index_profile_fingerprint"));
                value.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
                return value;
            })
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "检索运行不存在"));

        JsonNode plan = (JsonNode) run.get("query_plan");
        List<KnowledgeModels.RetrievalIntentView> intents = new ArrayList<>();
        List<String> contextParts = new ArrayList<>();
        for (JsonNode intent : plan.path("intents")) {
            String type = intent.path("type").asText();
            List<KnowledgeModels.RetrievalHitView> hits = jdbc.sql("""
                    select h.*, u.document_id, u.knowledge_source_id, u.title, u.content,
                           u.expected_predicates::text, u.source_locator::text,
                           u.source_version, u.content_hash
                    from retrieval_hits h
                    join knowledge_units u on u.id = h.knowledge_unit_id
                    where h.retrieval_run_id = :runId and h.query_intent = :intent
                    order by h.fusion_rank
                    """)
                .param("runId", runId)
                .param("intent", type)
                .query((rs, rowNum) -> mapHit(rs))
                .list();
            hits.stream().filter(KnowledgeModels.RetrievalHitView::selected)
                .forEach(hit -> contextParts.add(type + ":" + hit.knowledgeUnitId() + ":" + hit.contentHash()));
            intents.add(new KnowledgeModels.RetrievalIntentView(type, intent.path("query").asText(), hits));
        }
        String contextHash = Hashing.sha256(String.join("|", contextParts));
        return new KnowledgeModels.RetrievalRunView(
            run.get("id").toString(), (String) run.get("investigation_run_id"),
            run.get("index_version").toString(), (String) run.get("embedding_provider"),
            run.get("embedding_model").toString(), (String) run.get("embedding_model_digest"),
            (String) run.get("index_profile_fingerprint"),
            "COMPLETED", plan, (JsonNode) run.get("retrieval_config"), List.copyOf(intents),
            contextHash, (OffsetDateTime) run.get("created_at")
        );
    }

    public String currentIndexVersion(String domainPackKey) {
        return indexVersion(domainPackKey);
    }

    public void assertReadyForInvestigation(
        DomainPackDefinition definition,
        boolean allowTestEmbedding
    ) {
        List<String> reasons = indexReadinessReasons(definition, allowTestEmbedding);
        if (!reasons.isEmpty()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "DOMAIN_PACK_INDEX_NOT_READY",
                "领域包知识索引尚未达到调查就绪条件",
                Map.of(
                    "domain_pack", definition.scopedKey(),
                    "embedding_provider", model.embeddingProviderName(),
                    "embedding_model", model.embeddingModel(),
                    "reasons", reasons
                )
            );
        }
    }

    public List<String> indexReadinessReasons(
        DomainPackDefinition definition,
        boolean allowTestEmbedding
    ) {
        IndexReadinessState state = jdbc.sql("""
                select s.status, s.pack_fingerprint, s.index_profile_fingerprint,
                       count(u.id) unit_count,
                       count(u.embedding) embedding_count
                from knowledge_sources s
                left join knowledge_units u on u.knowledge_source_id = s.id
                where s.workspace_id = :workspaceId
                  and s.domain_pack_key = :domainPackKey
                  and s.version = :version
                group by s.id, s.status, s.pack_fingerprint, s.index_profile_fingerprint,
                         s.created_at
                order by s.created_at desc
                limit 1
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("domainPackKey", definition.scopedKey())
            .param("version", definition.version())
            .query((rs, rowNum) -> new IndexReadinessState(
                rs.getString("status"),
                rs.getString("pack_fingerprint"),
                rs.getString("index_profile_fingerprint"),
                rs.getInt("unit_count"),
                rs.getInt("embedding_count")
            ))
            .optional()
            .orElse(null);
        List<String> reasons = new ArrayList<>();
        if (state == null) {
            reasons.add("KNOWLEDGE_NOT_INDEXED");
        } else {
            if (!"PUBLISHED".equals(state.status())) reasons.add("KNOWLEDGE_NOT_PUBLISHED");
            if (!definition.fingerprint().equals(state.packFingerprint())) {
                reasons.add("DOMAIN_PACK_FINGERPRINT_MISMATCH");
            }
            if (!indexProfileFingerprint(definition).equals(state.indexProfileFingerprint())) {
                reasons.add("INDEX_PROFILE_MISMATCH");
            }
            int unitCount = state.unitCount();
            if (definition.content().manifest().path("knowledge").path("required").asBoolean(false)
                && unitCount == 0) {
                reasons.add("KNOWLEDGE_UNITS_MISSING");
            }
            if ("required".equals(definition.vectorPolicy())) {
                int embeddingCount = state.embeddingCount();
                if (unitCount == 0 || embeddingCount != unitCount) {
                    reasons.add("VECTOR_INDEX_NOT_READY");
                }
                if (!model.embeddingProductionReady() && !allowTestEmbedding) {
                    reasons.add("PRODUCTION_EMBEDDING_NOT_READY");
                }
            }
        }
        return List.copyOf(reasons);
    }

    private List<Candidate> hybrid(
        String query,
        String queryInstruction,
        String domainPackKey,
        int keywordTopK,
        int vectorTopK,
        int rrfK,
        double applicabilityMultiplier,
        String eventType,
        Set<String> observedPredicates,
        boolean vectorEnabled
    ) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        String eventApplicability = json.write(Map.of("event_types", List.of(eventType)));
        String keywordQuery = keywordQuery(query);
        List<RankedRow> keywordRows = jdbc.sql("""
                select u.id, u.document_id, u.title, u.applicability::text,
                       u.expected_predicates::text,
                       ts_rank_cd(u.content_tsv, websearch_to_tsquery('simple', :query)) score
                from knowledge_units u
                join knowledge_sources s on s.id = u.knowledge_source_id
                where u.workspace_id = :workspaceId and u.domain_pack_key = :domainPackKey
                  and u.status = 'PUBLISHED' and s.status = 'PUBLISHED'
                  and u.applicability @> cast(:eventApplicability as jsonb)
                  and u.content_tsv @@ websearch_to_tsquery('simple', :query)
                order by score desc, u.id
                limit :limit
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("domainPackKey", domainPackKey)
            .param("eventApplicability", eventApplicability)
            .param("query", keywordQuery)
            .param("limit", keywordTopK)
            .query((rs, rowNum) -> new RankedRow(
                rs.getString("id"), rs.getString("document_id"), rs.getString("title"),
                json.read(rs.getString("applicability")),
                json.read(rs.getString("expected_predicates")),
                rowNum + 1, rs.getDouble("score")
            ))
            .list();
        for (RankedRow row : keywordRows) {
            Candidate value = candidates.computeIfAbsent(row.id(), ignored -> new Candidate(row));
            value.keywordRank = row.rank();
            value.keywordScore = row.score();
        }

        List<RankedRow> vectorRows = vectorEnabled ? jdbc.sql("""
                select u.id, u.document_id, u.title, u.applicability::text,
                       u.expected_predicates::text,
                       1 - (u.embedding <=> cast(:embedding as vector)) score
                from knowledge_units u
                join knowledge_sources s on s.id = u.knowledge_source_id
                where u.workspace_id = :workspaceId and u.domain_pack_key = :domainPackKey
                  and u.status = 'PUBLISHED' and s.status = 'PUBLISHED'
                  and u.applicability @> cast(:eventApplicability as jsonb)
                  and u.embedding is not null
                order by u.embedding <=> cast(:embedding as vector), u.id
                limit :limit
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("domainPackKey", domainPackKey)
            .param("eventApplicability", eventApplicability)
            .param("embedding", vectorLiteral(model.embeddingQuery(query, queryInstruction)))
            .param("limit", vectorTopK)
            .query((rs, rowNum) -> new RankedRow(
                rs.getString("id"), rs.getString("document_id"), rs.getString("title"),
                json.read(rs.getString("applicability")),
                json.read(rs.getString("expected_predicates")),
                rowNum + 1, rs.getDouble("score")
            ))
            .list() : List.of();
        for (RankedRow row : vectorRows) {
            Candidate value = candidates.computeIfAbsent(row.id(), ignored -> new Candidate(row));
            value.vectorRank = row.rank();
            value.vectorScore = row.score();
        }

        for (Candidate candidate : candidates.values()) {
            double rrf = 0;
            if (candidate.keywordRank != null) {
                rrf += 1.0 / (rrfK + candidate.keywordRank);
            }
            if (candidate.vectorRank != null) {
                rrf += 1.0 / (rrfK + candidate.vectorRank);
            }
            boolean contextMatch = containsAny(
                candidate.applicability.path("context_predicates"), observedPredicates
            );
            candidate.applicabilityScore = contextMatch ? applicabilityMultiplier : 1.0;
            candidate.applicabilityReason = contextMatch
                ? "EVENT_AND_CONTEXT_MATCH"
                : "EVENT_MATCH";
            candidate.fusionScore = rrf * candidate.applicabilityScore;
        }
        return candidates.values().stream()
            .sorted(Comparator.comparingDouble((Candidate value) -> value.fusionScore).reversed()
                .thenComparing(value -> value.unitId))
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String keywordQuery(String query) {
        var matcher = KEYWORD_TERM.matcher(query == null ? "" : query);
        List<String> terms = new ArrayList<>();
        while (matcher.find() && terms.size() < MAX_KEYWORD_TERMS) {
            String term = matcher.group();
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return String.join(" OR ", terms);
    }

    private static void select(
        List<Candidate> candidates,
        int finalTopK,
        int maxUnitsPerDocument,
        double minimumScore
    ) {
        Map<String, Integer> perDocument = new HashMap<>();
        int selected = 0;
        for (Candidate candidate : candidates) {
            int count = perDocument.getOrDefault(candidate.documentId, 0);
            if (candidate.fusionScore < minimumScore) {
                candidate.selectionReason = "BELOW_MINIMUM_SCORE";
            } else if (selected < finalTopK && count < maxUnitsPerDocument) {
                candidate.selected = true;
                candidate.selectionReason = "RRF_TOP_K_AND_SOURCE_DIVERSITY";
                perDocument.put(candidate.documentId, count + 1);
                selected++;
            } else {
                candidate.selectionReason = count >= maxUnitsPerDocument
                    ? "SOURCE_DIVERSITY_LIMIT"
                    : "BELOW_FINAL_TOP_K";
            }
        }
    }

    private void replaceUnits(
        String documentId,
        String sourceId,
        String domainPackKey,
        String sourceVersion,
        String documentTitle,
        String markdown,
        String aliases,
        String externalId,
        JsonNode applicability,
        JsonNode expectedPredicates,
        DomainPackDefinition definition
    ) {
        jdbc.sql("delete from knowledge_units where document_id = :documentId")
            .param("documentId", documentId)
            .update();
        List<MarkdownUnit> units = splitMarkdown(markdown, documentTitle);
        List<String> embeddingInputs = units.stream()
            .map(unit -> unit.title() + " " + unit.content() + " " + aliases)
            .toList();
        boolean vectorEnabled = !"disabled".equals(definition.vectorPolicy());
        List<double[]> embeddings = vectorEnabled
            ? model.embeddingDocuments(embeddingInputs)
            : List.of();
        int position = 0;
        for (MarkdownUnit unit : units) {
            position++;
            String unitId = ids.next("ku");
            String searchable = unit.content() + " " + aliases;
            String contentHash = Hashing.sha256(unit.content());
            ObjectNode locator = mapper.createObjectNode();
            locator.put("document_id", externalId);
            locator.put("section", unit.title());
            locator.put("position", position);
            locator.put("segmenter", SEGMENTER_VERSION);
            jdbc.sql("""
                    insert into knowledge_units(
                        id, workspace_id, knowledge_source_id, document_id, domain_pack_key,
                        topic, title, content, search_text, embedding, applicability,
                        expected_predicates, source_locator, source_version, content_hash, status
                    ) values (
                        :id, :workspaceId, :sourceId, :documentId, :domainPackKey,
                        :topic, :title, :content, :searchText,
                        cast(nullif(:embedding, '') as vector),
                        cast(:applicability as jsonb), cast(:expectedPredicates as jsonb),
                        cast(:sourceLocator as jsonb), :sourceVersion, :contentHash, 'PUBLISHED'
                    )
                    """)
                .param("id", unitId)
                .param("workspaceId", InstanceScope.ID)
                .param("sourceId", sourceId)
                .param("documentId", documentId)
                .param("domainPackKey", domainPackKey)
                .param("topic", slug(unit.title()))
                .param("title", unit.title())
                .param("content", unit.content())
                .param("searchText", searchable)
                .param("embedding", vectorEnabled ? vectorLiteral(embeddings.get(position - 1)) : "")
                .param("applicability", json.write(applicability))
                .param("expectedPredicates", json.write(expectedPredicates))
                .param("sourceLocator", json.write(locator))
                .param("sourceVersion", sourceVersion)
                .param("contentHash", contentHash)
                .update();
        }
        jdbc.sql("update knowledge_documents set parse_status = 'PUBLISHED' where id = :id")
            .param("id", documentId)
            .update();
        jdbc.sql("""
                update knowledge_sources set status = 'PUBLISHED', published_at = coalesce(published_at, now())
                where id = :id
                """)
            .param("id", sourceId)
            .update();
    }

    private String insertDocument(
        String sourceId,
        String externalId,
        String title,
        String content,
        String language,
        JsonNode metadata
    ) {
        String id = ids.next("kd");
        String checksum = Hashing.sha256(content.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                insert into knowledge_documents(
                    id, knowledge_source_id, workspace_id, external_id, title, content_type,
                    checksum_sha256, language, parse_status, metadata
                ) values (
                    :id, :sourceId, :workspaceId, :externalId, :title, 'text/markdown',
                    :checksum, :language, 'PARSING', cast(:metadata as jsonb)
                )
                """)
            .param("id", id)
            .param("sourceId", sourceId)
            .param("workspaceId", InstanceScope.ID)
            .param("externalId", externalId)
            .param("title", title)
            .param("checksum", checksum)
            .param("language", language)
            .param("metadata", json.write(metadata))
            .update();
        return id;
    }

    private KnowledgeModels.SourceView source(String id) {
        return jdbc.sql("""
                select s.*,
                       (select count(*) from knowledge_documents d where d.knowledge_source_id = s.id) document_count,
                       (select count(*) from knowledge_units u where u.knowledge_source_id = s.id) unit_count
                from knowledge_sources s
                where s.id = :id and s.workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query(this::mapSource)
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "知识源不存在"));
    }

    private static String decodeUnitCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return "";
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (value.isBlank() || value.length() > 64) {
                throw new IllegalArgumentException("cursor shape");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "知识单元游标无效");
        }
    }

    private static String encodeUnitCursor(String id) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private static UsageCursor decodeUsageCursor(String cursor, String message) {
        if (cursor == null || cursor.isBlank()) {
            return new UsageCursor(USAGE_CURSOR_START, USAGE_CURSOR_KEY_START);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator < 1 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("cursor shape");
            }
            OffsetDateTime createdAt = OffsetDateTime.parse(decoded.substring(0, separator));
            String key = new String(
                Base64.getUrlDecoder().decode(decoded.substring(separator + 1)),
                StandardCharsets.UTF_8
            );
            if (key.isBlank() || key.length() > 200) {
                throw new IllegalArgumentException("cursor key");
            }
            return new UsageCursor(createdAt, key);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", message);
        }
    }

    private static String encodeUsageCursor(OffsetDateTime createdAt, String key) {
        String encodedKey = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        String raw = createdAt + "|" + encodedKey;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private void assertUnit(String id) {
        boolean exists = jdbc.sql("""
                select exists(
                    select 1 from knowledge_units where id = :id and workspace_id = :workspaceId
                )
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query(Boolean.class)
            .single();
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "知识单元不存在");
        }
    }

    private Map<String, Object> sourceRecord(String id) {
        return jdbc.sql("""
                select id, domain_pack_key, version from knowledge_sources
                where id = :id and workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> Map.<String, Object>of(
                "id", rs.getString("id"),
                "domain_pack_key", rs.getString("domain_pack_key"),
                "version", rs.getString("version")
            ))
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "知识源不存在"));
    }

    private String validateRetrievalContext(
        DomainPackDefinition definition,
        String eventType,
        Set<String> observedPredicates
    ) {
        DomainPackCatalog catalog = definition.content();
        String normalized = eventType == null ? "" : eventType.trim().toLowerCase(Locale.ROOT);
        boolean supported = false;
        for (JsonNode configured : catalog.manifest().path("event_types")) {
            if (configured.asText().equals(normalized)) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_RETRIEVAL_EVENT_TYPE",
                "检索事件类型不受当前领域包支持"
            );
        }
        if (observedPredicates != null) {
            for (String predicate : observedPredicates) {
                if (predicate == null || !catalog.vocabulary().path("predicates").has(predicate)) {
                    throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "UNKNOWN_RETRIEVAL_PREDICATE",
                        "检索上下文包含领域包未定义的 Predicate"
                    );
                }
            }
        }
        return normalized;
    }

    private static boolean containsAny(JsonNode values, Set<String> candidates) {
        if (!values.isArray() || candidates.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (candidates.contains(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private KnowledgeModels.DocumentView document(String id) {
        return jdbc.sql("""
                select d.*,
                       (select count(*) from knowledge_units u where u.document_id = d.id) unit_count
                from knowledge_documents d
                where d.id = :id and d.workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query(this::mapDocument)
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "知识文档不存在"));
    }

    private String indexVersion(String domainPackKey) {
        DomainPackDefinition definition = domainPacks.require(domainPackKey);
        String material = domainPackKey + "|" + indexProfileFingerprint(definition);
        return domainPackKey + ":" + Hashing.sha256(material).substring(0, 16);
    }

    private String indexProfileFingerprint(DomainPackDefinition definition) {
        String vectorProfile = "disabled".equals(definition.vectorPolicy())
            ? "disabled"
            : model.embeddingProviderName() + "|" + model.embeddingModel() + "|"
                + model.embeddingDimension() + "|" + model.embeddingModelDigest() + "|"
                + embeddingQueryInstruction(definition);
        return Hashing.sha256(
            definition.fingerprint() + "|" + SEGMENTER_VERSION + "|"
                + vectorProfile
        );
    }

    private String embeddingQueryInstruction(DomainPackDefinition definition) {
        String configured = definition.content().retrievalConfig()
            .path("embedding_query_instruction").asText();
        return configured.isBlank() ? model.embeddingQueryInstruction() : configured;
    }

    private Map<String, String> goldenAliases(DomainPackCatalog catalog) {
        Map<String, Set<String>> values = new HashMap<>();
        for (JsonNode query : catalog.goldenQueries().path("queries")) {
            for (JsonNode document : query.path("expected_document_ids")) {
                values.computeIfAbsent(document.asText(), ignored -> new LinkedHashSet<>())
                    .add(query.path("query").asText());
            }
        }
        return values.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> String.join(" ", entry.getValue())
        ));
    }

    private static String primaryLicense(JsonNode metadata) {
        if (metadata.path("licenses").isArray() && !metadata.path("licenses").isEmpty()) {
            return metadata.path("licenses").get(0).asText();
        }
        return metadata.path("license").asText();
    }

    private KnowledgeModels.EmbeddingProvenanceView embeddingProvenance(
        KnowledgeModels.SourceView source
    ) {
        if (source.embeddingProvider() == null || source.embeddingModel() == null
            || source.embeddingDimension() == null || source.embeddingModelDigest() == null
            || source.indexProfileFingerprint() == null) {
            return null;
        }
        DomainPackDefinition definition = domainPacks.require(source.domainPackKey());
        boolean productionReady = model.embeddingProductionReady()
            && Objects.equals(source.embeddingProvider(), model.embeddingProviderName())
            && Objects.equals(source.embeddingModel(), model.embeddingModel())
            && Objects.equals(source.embeddingDimension(), model.embeddingDimension())
            && Objects.equals(source.embeddingModelDigest(), model.embeddingModelDigest())
            && Objects.equals(source.indexProfileFingerprint(), indexProfileFingerprint(definition));
        return new KnowledgeModels.EmbeddingProvenanceView(
            source.embeddingProvider(),
            source.embeddingModel(),
            source.embeddingDimension(),
            source.embeddingModelDigest(),
            source.embeddingQueryInstruction() == null ? "" : source.embeddingQueryInstruction(),
            source.indexProfileFingerprint(),
            productionReady
        );
    }

    private void reindexSource(String sourceId, DomainPackDefinition definition) {
        if ("disabled".equals(definition.vectorPolicy())) {
            jdbc.sql("update knowledge_units set embedding = null where knowledge_source_id = :sourceId")
                .param("sourceId", sourceId)
                .update();
            return;
        }
        List<EmbeddingRow> rows = jdbc.sql("""
                select id, title, content, search_text from knowledge_units
                where knowledge_source_id = :sourceId
                order by id
                """)
            .param("sourceId", sourceId)
            .query((rs, rowNum) -> new EmbeddingRow(
                rs.getString("id"),
                rs.getString("title") + " " + rs.getString("content") + " "
                    + rs.getString("search_text")
            ))
            .list();
        if (rows.isEmpty()) {
            return;
        }
        List<double[]> vectors = model.embeddingDocuments(rows.stream().map(EmbeddingRow::text).toList());
        for (int index = 0; index < rows.size(); index++) {
            jdbc.sql("update knowledge_units set embedding = cast(:embedding as vector) where id = :id")
                .param("embedding", vectorLiteral(vectors.get(index)))
                .param("id", rows.get(index).id())
                .update();
        }
    }

    private KnowledgeModels.SourceView mapSource(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeModels.SourceView(
            rs.getString("id"), rs.getString("domain_pack_key"), rs.getString("name"),
            rs.getString("source_type"), rs.getString("version"), rs.getString("license"),
            rs.getString("status"), rs.getBoolean("fixture_only"), rs.getBoolean("production_allowed"),
            rs.getInt("document_count"), rs.getInt("unit_count"),
            rs.getString("embedding_provider"), rs.getString("embedding_model"),
            (Integer) rs.getObject("embedding_dimension"), rs.getString("embedding_model_digest"),
            rs.getString("embedding_query_instruction"), rs.getString("index_profile_fingerprint"),
            rs.getObject("published_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private KnowledgeModels.DocumentView mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeModels.DocumentView(
            rs.getString("id"), rs.getString("knowledge_source_id"), rs.getString("external_id"),
            rs.getString("title"), rs.getString("content_type"), rs.getString("checksum_sha256"),
            rs.getString("language"), rs.getString("parse_status"), json.read(rs.getString("metadata")),
            rs.getInt("unit_count"), rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private KnowledgeModels.RetrievalHitView mapHit(ResultSet rs) throws SQLException {
        Number keywordScore = (Number) rs.getObject("keyword_score");
        Number vectorScore = (Number) rs.getObject("vector_score");
        Number keywordRank = (Number) rs.getObject("keyword_rank");
        Number vectorRank = (Number) rs.getObject("vector_rank");
        return new KnowledgeModels.RetrievalHitView(
            rs.getString("knowledge_unit_id"), rs.getString("document_id"),
            rs.getString("knowledge_source_id"), rs.getString("title"), rs.getString("content"),
            keywordRank == null ? null : keywordRank.intValue(),
            keywordScore == null ? null : keywordScore.doubleValue(),
            vectorRank == null ? null : vectorRank.intValue(),
            vectorScore == null ? null : vectorScore.doubleValue(),
            rs.getInt("fusion_rank"), rs.getDouble("fusion_score"),
            rs.getDouble("applicability_score"), rs.getString("applicability_reason"),
            stringList(json.read(rs.getString("expected_predicates"))), rs.getBoolean("selected"),
            rs.getString("selection_reason"), json.read(rs.getString("source_locator")),
            rs.getString("source_version"), rs.getString("content_hash")
        );
    }

    private static List<MarkdownUnit> splitMarkdown(String markdown, String fallbackTitle) {
        List<MarkdownUnit> units = new ArrayList<>();
        String title = fallbackTitle;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("## ")) {
                flushUnit(units, title, body);
                title = line.substring(3).trim();
            } else if (!line.startsWith("# ")) {
                body.append(line).append('\n');
            }
        }
        flushUnit(units, title, body);
        if (units.isEmpty()) {
            units.add(new MarkdownUnit(fallbackTitle, markdown.trim()));
        }
        return List.copyOf(units);
    }

    private static void flushUnit(List<MarkdownUnit> units, String title, StringBuilder body) {
        String content = body.toString().trim();
        if (!content.isBlank()) {
            units.add(new MarkdownUnit(title, content));
        }
        body.setLength(0);
    }

    private static String firstHeading(String markdown) {
        return markdown.lines().filter(line -> line.startsWith("# "))
            .map(line -> line.substring(2).trim())
            .findFirst()
            .orElse("Untitled knowledge document");
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    }

    private static String vectorLiteral(double[] vector) {
        StringBuilder value = new StringBuilder(vector.length * 10).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(String.format(Locale.ROOT, "%.8f", vector[index]));
        }
        return value.append(']').toString();
    }

    private static List<String> stringList(JsonNode value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        });
        return List.copyOf(values);
    }

    public record QueryIntent(String type, String query) {}
    private record IntentCandidates(QueryIntent intent, List<Candidate> candidates) {}
    private record UsageCursor(OffsetDateTime createdAt, String key) {}
    private record RankedRow(
        String id,
        String documentId,
        String title,
        JsonNode applicability,
        JsonNode expectedPredicates,
        int rank,
        double score
    ) {}
    private record MarkdownUnit(String title, String content) {}
    private record SourceImportState(String id, String fingerprint, String indexProfileFingerprint) {}
    private record IndexReadinessState(
        String status,
        String packFingerprint,
        String indexProfileFingerprint,
        int unitCount,
        int embeddingCount
    ) {}
    private record DocumentImportState(String id, String checksum) {}
    private record EmbeddingRow(String id, String text) {}

    private static final class Candidate {
        private final String unitId;
        private final String documentId;
        private final String title;
        private final JsonNode applicability;
        private final JsonNode expectedPredicates;
        private Integer keywordRank;
        private Double keywordScore;
        private Integer vectorRank;
        private Double vectorScore;
        private int fusionRank;
        private double fusionScore;
        private double applicabilityScore;
        private String applicabilityReason;
        private boolean selected;
        private String selectionReason;

        private Candidate(RankedRow row) {
            this.unitId = row.id();
            this.documentId = row.documentId();
            this.title = row.title();
            this.applicability = row.applicability();
            this.expectedPredicates = row.expectedPredicates();
        }
    }
}
