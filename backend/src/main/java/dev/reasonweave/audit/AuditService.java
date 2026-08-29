package dev.reasonweave.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.JsonSupport;
import dev.reasonweave.shared.ids.IdGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final int EXPORT_BATCH_SIZE = 500;
    private static final OffsetDateTime CURSOR_START = OffsetDateTime.parse("9999-12-31T23:59:59Z");
    private static final String CURSOR_ID_START = "\uffff";
    private final JdbcClient jdbc;
    private final JsonSupport json;
    private final IdGenerator ids;

    public AuditService(JdbcClient jdbc, JsonSupport json, IdGenerator ids) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
    }

    public void record(
        String eventId,
        String action,
        String resourceType,
        String resourceId,
        Object before,
        Object after,
        String requestId
    ) {
        recordWithActor(
            eventId,
            action,
            resourceType,
            resourceId,
            before,
            after,
            requestId,
            Map.of("type", "api", "id", "local_api")
        );
    }

    public void recordSystem(
        String eventId,
        String action,
        String resourceType,
        String resourceId,
        Object before,
        Object after,
        String requestId
    ) {
        recordWithActor(
            eventId,
            action,
            resourceType,
            resourceId,
            before,
            after,
            requestId,
            Map.of("type", "system", "id", "reasonweave")
        );
    }

    private void recordWithActor(
        String eventId,
        String action,
        String resourceType,
        String resourceId,
        Object before,
        Object after,
        String requestId,
        Map<String, String> actor
    ) {
        jdbc.sql("""
                insert into audit_events(
                    id, workspace_id, event_id, actor, action, resource,
                    before_state, after_state, request_id
                ) values (
                    :id, :workspaceId, :eventId, cast(:actor as jsonb), :action,
                    cast(:resource as jsonb), cast(:beforeState as jsonb),
                    cast(:afterState as jsonb), :requestId
                )
                """)
            .param("id", ids.next("aud"))
            .param("workspaceId", InstanceScope.ID)
            .param("eventId", eventId)
            .param("actor", json.write(actor))
            .param("action", action)
            .param("resource", json.write(Map.of("type", resourceType, "id", resourceId)))
            .param("beforeState", json.write(before == null ? Map.of() : before))
            .param("afterState", json.write(after == null ? Map.of() : after))
            .param("requestId", requestId)
            .update();
    }

    public AuditModels.AuditPage list(
        String eventId,
        String cursor,
        int requestedLimit,
        String actorId,
        String action,
        String runId
    ) {
        assertEvent(eventId);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        Cursor decoded = decode(cursor);
        List<AuditModels.AuditEntry> rows = query(
            eventId, actorId, action, runId, decoded.occurredAt(), decoded.id(), limit + 1
        );
        String nextCursor = null;
        if (rows.size() > limit) {
            AuditModels.AuditEntry boundary = rows.get(limit - 1);
            nextCursor = encode(boundary.occurredAt(), boundary.id());
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new AuditModels.AuditPage(List.copyOf(rows), nextCursor, limit);
    }

    public void writeJsonLines(
        String eventId,
        String actorId,
        String action,
        String runId,
        OutputStream output
    ) throws IOException {
        assertEvent(eventId);
        OffsetDateTime cursorAt = CURSOR_START;
        String cursorId = CURSOR_ID_START;
        while (true) {
            List<AuditModels.AuditEntry> rows = query(
                eventId, actorId, action, runId, cursorAt, cursorId, EXPORT_BATCH_SIZE
            );
            if (rows.isEmpty()) {
                break;
            }
            for (AuditModels.AuditEntry row : rows) {
                output.write(json.write(row).getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            }
            output.flush();
            AuditModels.AuditEntry boundary = rows.getLast();
            cursorAt = boundary.occurredAt();
            cursorId = boundary.id();
            if (rows.size() < EXPORT_BATCH_SIZE) {
                break;
            }
        }
    }

    private List<AuditModels.AuditEntry> query(
        String eventId,
        String actorId,
        String action,
        String runId,
        OffsetDateTime cursorAt,
        String cursorId,
        int limit
    ) {
        return jdbc.sql("""
                select id, actor::text, action, resource::text, before_state::text,
                       after_state::text, request_id, occurred_at
                from audit_events
                where workspace_id = :workspaceId and event_id = :eventId
                  and (:actorId = '' or actor ->> 'id' = :actorId)
                  and (:action = '' or action = :action)
                  and (
                    :runId = ''
                    or resource ->> 'id' = :runId
                    or before_state ->> 'investigation_run_id' = :runId
                    or after_state ->> 'investigation_run_id' = :runId
                  )
                  and (occurred_at, id) < (:cursorAt, :cursorId)
                order by occurred_at desc, id desc
                limit :limit
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("eventId", eventId)
            .param("actorId", defaultText(actorId))
            .param("action", defaultText(action))
            .param("runId", defaultText(runId))
            .param("cursorAt", cursorAt)
            .param("cursorId", cursorId)
            .param("limit", limit)
            .query((rs, rowNum) -> new AuditModels.AuditEntry(
                rs.getString("id"), read(rs.getString("actor")), rs.getString("action"),
                read(rs.getString("resource")), read(rs.getString("before_state")),
                read(rs.getString("after_state")), rs.getString("request_id"),
                rs.getObject("occurred_at", OffsetDateTime.class)
            ))
            .list();
    }

    private void assertEvent(String eventId) {
        boolean exists = jdbc.sql("""
                select exists(
                    select 1 from events where id = :eventId and workspace_id = :workspaceId
                )
                """)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .query(Boolean.class)
            .single();
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "事件不存在");
        }
    }

    private JsonNode read(String value) {
        return value == null ? json.read("{}") : json.read(value);
    }

    private static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return new Cursor(CURSOR_START, CURSOR_ID_START);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator < 1 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("cursor shape");
            }
            return new Cursor(OffsetDateTime.parse(decoded.substring(0, separator)), decoded.substring(separator + 1));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "审计游标无效");
        }
    }

    private static String encode(OffsetDateTime occurredAt, String id) {
        String raw = occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String defaultText(String value) {
        return value == null ? "" : value;
    }

    private record Cursor(OffsetDateTime occurredAt, String id) {}
}
