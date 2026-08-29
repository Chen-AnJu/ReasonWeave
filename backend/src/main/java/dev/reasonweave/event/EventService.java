package dev.reasonweave.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reasonweave.audit.AuditService;
import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.contracts.EventIrValidator;
import dev.reasonweave.domainpack.DomainPackDefinition;
import dev.reasonweave.domainpack.DomainEventValidator;
import dev.reasonweave.domainpack.DomainPackRegistry;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.investigation.InvestigationModels;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.Hashing;
import dev.reasonweave.shared.JsonSupport;
import dev.reasonweave.shared.PageCursor;
import dev.reasonweave.shared.ids.IdGenerator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final JdbcClient jdbc;
    private final EventIrValidator validator;
    private final JsonSupport json;
    private final IdGenerator ids;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final DomainPackRegistry domainPacks;
    private final DomainEventValidator domainEventValidator;
    private final ReasonWeaveProperties properties;

    public EventService(
        JdbcClient jdbc,
        EventIrValidator validator,
        JsonSupport json,
        IdGenerator ids,
        AuditService audit,
        ObjectMapper mapper,
        DomainPackRegistry domainPacks,
        DomainEventValidator domainEventValidator,
        ReasonWeaveProperties properties
    ) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.json = json;
        this.ids = ids;
        this.audit = audit;
        this.mapper = mapper;
        this.domainPacks = domainPacks;
        this.domainEventValidator = domainEventValidator;
        this.properties = properties;
    }

    @Transactional
    public EventModels.EventDetail create(JsonNode eventIr, String requestId) {
        validator.validate(eventIr);
        JsonNode event = eventIr.path("event");
        String eventType = text(event, "type", "");
        String domainPackKey = text(event, "domain_pack", "");
        DomainPackDefinition definition = requireSupported(eventType, domainPackKey);
        domainEventValidator.validateEvent(eventIr, definition, eventType);
        String id = ids.next("evt");
        String referenceCode = text(event, "reference_code", "EVT-" + id.substring(id.length() - 6));
        if (referenceCode.length() > 32) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "外部事件编号不能超过 32 个字符"
            );
        }
        String title = text(event, "title", "未命名事件");
        String description = nullableText(event, "description");
        JsonNode occurred = event.path("occurred_at");
        JsonNode location = event.path("location");

        jdbc.sql("""
                insert into events(
                    id, workspace_id, reference_code, event_type, title, description,
                    occurred_start, occurred_end, location_name, latitude, longitude,
                    status, domain_pack_key, event_ir
                ) values (
                    :id, :workspaceId, :referenceCode, :eventType, :title, :description,
                    :occurredStart, :occurredEnd, :locationName, :latitude, :longitude,
                    'DRAFT', :domainPackKey, cast(:eventIr as jsonb)
                )
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .param("referenceCode", referenceCode)
            .param("eventType", eventType)
            .param("title", title)
            .param("description", description)
            .param("occurredStart", parseDate(nullableText(occurred, "start")))
            .param("occurredEnd", parseDate(nullableText(occurred, "end")))
            .param("locationName", nullableText(location, "name"))
            .param("latitude", number(location, "latitude"))
            .param("longitude", number(location, "longitude"))
            .param("domainPackKey", domainPackKey)
            .param("eventIr", json.write(eventIr))
            .update();

        EventModels.EventDetail created = get(id);
        audit.record(id, "event.created", "event", id, null, created, requestId);
        return created;
    }

    public EventModels.EventPage list(String query, String status, String cursor, int requestedLimit) {
        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedStatus = status == null ? "" : status.trim();
        int limit = PageCursor.limit(requestedLimit);
        String scope = Hashing.sha256(normalizedQuery + "\n" + normalizedStatus);
        List<String> decoded = PageCursor.decode(cursor, scope, 2);
        OffsetDateTime cursorAt = OffsetDateTime.parse("9999-12-31T23:59:59Z");
        String cursorId = "\uffff";
        if (!decoded.isEmpty()) {
            try {
                cursorAt = OffsetDateTime.parse(decoded.get(0));
                cursorId = decoded.get(1);
            } catch (RuntimeException exception) {
                throw PageCursor.invalidCursor();
            }
        }
        List<EventModels.EventSummary> rows = jdbc.sql("""
                select e.*,
                       (select count(*) from evidence ev where ev.event_id = e.id) as evidence_count,
                       latest.score as latest_score,
                       latest.coverage as latest_coverage,
                       latest.title as top_hypothesis
                from events e
                left join lateral (
                    select h.score, h.evidence_coverage as coverage, h.title
                    from investigation_runs ir
                    join hypotheses h on h.investigation_run_id = ir.id
                    where ir.event_id = e.id and ir.status = 'COMPLETED'
                    order by ir.sequence_no desc, h.score desc
                    limit 1
                ) latest on true
                where e.workspace_id = :workspaceId
                  and (:status = '' or e.status = :status)
                  and (:query = '' or lower(e.title) like lower('%' || :query || '%')
                       or lower(e.reference_code) like lower('%' || :query || '%'))
                  and (e.updated_at, e.id) < (:cursorAt, :cursorId)
                order by e.updated_at desc, e.id desc
                limit :limit
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("query", normalizedQuery)
            .param("status", normalizedStatus)
            .param("cursorAt", cursorAt)
            .param("cursorId", cursorId)
            .param("limit", limit + 1)
            .query(this::mapSummary)
            .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            EventModels.EventSummary boundary = rows.get(limit - 1);
            nextCursor = PageCursor.encode(scope, boundary.updatedAt().toString(), boundary.id());
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        long total = jdbc.sql("""
                select count(*) from events e
                where e.workspace_id = :workspaceId
                  and (:status = '' or e.status = :status)
                  and (:query = '' or lower(e.title) like lower('%' || :query || '%')
                       or lower(e.reference_code) like lower('%' || :query || '%'))
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("query", normalizedQuery)
            .param("status", normalizedStatus)
            .query(Long.class)
            .single();
        return new EventModels.EventPage(List.copyOf(rows), nextCursor, limit, total);
    }

    public EventModels.EventDetail get(String id) {
        return jdbc.sql("""
                select * from events
                where id = :id and workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query(this::mapDetail)
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "事件不存在"));
    }

    public EventModels.EventView view(String id) {
        EventModels.EventDetail event = get(id);
        List<EventModels.EvidenceProjection> evidence = jdbc.sql("""
                select id, type, source, status, original_name, content_type,
                       generation, reliability, created_at
                from evidence
                where event_id = :eventId and workspace_id = :workspaceId
                order by created_at desc
                """)
            .param("eventId", id)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> new EventModels.EvidenceProjection(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("original_name"),
                rs.getString("content_type"),
                rs.getInt("generation"),
                rs.getDouble("reliability"),
                rs.getObject("created_at", OffsetDateTime.class)
            ))
            .list();

        EventModels.LatestInvestigationView latest = jdbc.sql("""
                select id, sequence_no, status, event_version,
                       evidence_snapshot_schema_version, evidence_snapshot_hash, evidence_snapshot::text,
                       knowledge_index_version, retrieval_run_id, result_snapshot::text, completed_at
                from investigation_runs
                where event_id = :eventId
                order by sequence_no desc
                limit 1
                """)
            .param("eventId", id)
            .query((rs, rowNum) -> mapLatestInvestigation(rs))
            .optional()
            .orElse(null);

        boolean stale = latest != null && (
            latest.eventVersion() < event.version()
                || hasEvidenceAfter(id, latest.completedAt())
        );

        List<EventModels.GapSummary> gaps = latest == null ? List.of() : jdbc.sql("""
                select id, title, estimated_impact, acquisition_cost, priority_score, status
                from evidence_gaps
                where investigation_run_id = :runId and status = 'OPEN'
                order by priority_score desc
                """)
            .param("runId", latest.id())
            .query((rs, rowNum) -> new EventModels.GapSummary(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("estimated_impact"),
                rs.getString("acquisition_cost"),
                rs.getDouble("priority_score"),
                rs.getString("status")
            ))
            .list();

        return new EventModels.EventView(event, evidence, latest, stale, gaps);
    }

    private EventModels.LatestInvestigationView mapLatestInvestigation(ResultSet rs) throws SQLException {
        int snapshotVersion = rs.getInt("evidence_snapshot_schema_version");
        JsonNode resultNode = rs.getString("result_snapshot") == null
            ? mapper.createObjectNode()
            : json.read(rs.getString("result_snapshot"));
        InvestigationModels.EvidenceSnapshotView snapshot;
        if (rs.getString("evidence_snapshot") != null) {
            snapshot = mapper.convertValue(
                json.read(rs.getString("evidence_snapshot")),
                InvestigationModels.EvidenceSnapshotView.class
            );
        } else {
            List<String> evidenceIds = new java.util.ArrayList<>();
            resultNode.path("evidence_snapshot").path("evidence_ids")
                .forEach(value -> evidenceIds.add(value.asText()));
            snapshot = new InvestigationModels.EvidenceSnapshotView(
                snapshotVersion,
                List.copyOf(evidenceIds),
                List.of()
            );
        }
        InvestigationModels.InvestigationResultView result;
        if (resultNode.isEmpty()) {
            result = new InvestigationModels.InvestigationResultView(
                "支持指数不是概率，只表示当前证据与规则下的相对支持程度",
                "",
                "query-plan-v1",
                rs.getLong("event_version"),
                rs.getString("evidence_snapshot_hash"),
                rs.getString("knowledge_index_version"),
                rs.getString("retrieval_run_id"),
                "",
                snapshot,
                List.of(),
                List.of()
            );
        } else {
            ObjectNode normalized = resultNode.deepCopy();
            normalized.set("evidence_snapshot", mapper.valueToTree(snapshot));
            result = mapper.convertValue(normalized, InvestigationModels.InvestigationResultView.class);
        }
        return new EventModels.LatestInvestigationView(
            rs.getString("id"),
            rs.getInt("sequence_no"),
            rs.getString("status"),
            rs.getLong("event_version"),
            snapshotVersion,
            rs.getString("evidence_snapshot_hash"),
            rs.getString("knowledge_index_version"),
            result,
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }

    public void markUpdated(String eventId) {
        jdbc.sql("""
                update events
                set version = version + 1,
                    updated_at = now(),
                    status = case when status = 'DRAFT' then 'COLLECTING' else status end
                where id = :id and workspace_id = :workspaceId
                """)
            .param("id", eventId)
            .param("workspaceId", InstanceScope.ID)
            .update();
    }

    public void assertSupported(EventModels.EventDetail event) {
        requireSupported(event.eventType(), event.domainPackKey());
    }

    private DomainPackDefinition requireSupported(String eventType, String domainPackKey) {
        DomainPackDefinition definition = domainPacks.requireForEvent(domainPackKey, eventType);
        if (!definition.productionAllowed() && !properties.seedFixtures()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DOMAIN_PACK_NOT_PRODUCTION",
                "该领域包未声明为可用于正式调查",
                Map.of("domain_pack", definition.scopedKey())
            );
        }
        return definition;
    }

    private boolean hasEvidenceAfter(String eventId, OffsetDateTime completedAt) {
        if (completedAt == null) {
            return false;
        }
        return jdbc.sql("""
                select exists(
                    select 1 from evidence
                    where event_id = :eventId and created_at > :completedAt
                )
                """)
            .param("eventId", eventId)
            .param("completedAt", completedAt)
            .query(Boolean.class)
            .single();
    }

    private EventModels.EventSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        Number latestCoverage = (Number) rs.getObject("latest_coverage");
        return new EventModels.EventSummary(
            rs.getString("id"), rs.getString("reference_code"), rs.getString("event_type"),
            rs.getString("title"), rs.getString("description"), rs.getString("status"),
            rs.getString("domain_pack_key"), rs.getString("location_name"),
            rs.getObject("occurred_start", OffsetDateTime.class),
            rs.getObject("occurred_end", OffsetDateTime.class), rs.getLong("version"),
            rs.getInt("evidence_count"), (Integer) rs.getObject("latest_score"),
            latestCoverage == null ? null : latestCoverage.doubleValue(), rs.getString("top_hypothesis"),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private EventModels.EventDetail mapDetail(ResultSet rs, int rowNum) throws SQLException {
        Number latitude = (Number) rs.getObject("latitude");
        Number longitude = (Number) rs.getObject("longitude");
        return new EventModels.EventDetail(
            rs.getString("id"), rs.getString("reference_code"),
            rs.getString("event_type"), rs.getString("title"), rs.getString("description"),
            rs.getString("status"), rs.getString("domain_pack_key"), rs.getString("location_name"),
            latitude == null ? null : latitude.doubleValue(),
            longitude == null ? null : longitude.doubleValue(),
            rs.getObject("occurred_start", OffsetDateTime.class),
            rs.getObject("occurred_end", OffsetDateTime.class), rs.getLong("version"),
            json.read(rs.getString("event_ir")), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = nullableText(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static OffsetDateTime parseDate(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value);
    }

}
