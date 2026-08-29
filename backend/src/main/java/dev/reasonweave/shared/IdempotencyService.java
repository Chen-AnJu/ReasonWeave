package dev.reasonweave.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 200;
    private final JdbcClient jdbc;
    private final JsonSupport json;

    public IdempotencyService(JdbcClient jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Outcome execute(
        String workspaceId,
        String endpoint,
        String key,
        String requestHash,
        int responseStatus,
        Supplier<?> action
    ) {
        String normalizedKey = requireKey(key);
        advisoryLock(workspaceId, endpoint, normalizedKey);
        removeExpired(workspaceId, endpoint, normalizedKey);
        StoredRecord existing = find(workspaceId, endpoint, normalizedKey);
        if (existing != null) {
            assertSameRequest(existing, requestHash, endpoint);
            if (!"COMPLETED".equals(existing.state()) || existing.responseBody() == null) {
                throw inProgress(endpoint, existing.resourceId());
            }
            return new Outcome(existing.responseStatus(), existing.responseBody(), true);
        }

        Object created = action.get();
        JsonNode body = json.read(json.write(created));
        jdbc.sql("""
                insert into idempotency_records(
                    workspace_id, endpoint, idempotency_key, request_hash, state,
                    response_status, response_body, expires_at
                ) values (
                    :workspaceId, :endpoint, :key, :requestHash, 'COMPLETED',
                    :responseStatus, cast(:responseBody as jsonb), :expiresAt
                )
                """)
            .param("workspaceId", workspaceId)
            .param("endpoint", endpoint)
            .param("key", normalizedKey)
            .param("requestHash", requestHash)
            .param("responseStatus", responseStatus)
            .param("responseBody", json.write(body))
            .param("expiresAt", OffsetDateTime.now().plusHours(24))
            .update();
        return new Outcome(responseStatus, body, false);
    }

    public Claim claim(String workspaceId, String endpoint, String key, String requestHash) {
        String normalizedKey = requireKey(key);
        removeExpired(workspaceId, endpoint, normalizedKey);
        int inserted = jdbc.sql("""
                insert into idempotency_records(
                    workspace_id, endpoint, idempotency_key, request_hash, state,
                    response_status, response_body, expires_at
                ) values (
                    :workspaceId, :endpoint, :key, :requestHash, 'IN_PROGRESS',
                    null, null, :expiresAt
                )
                on conflict (workspace_id, endpoint, idempotency_key) do nothing
                """)
            .param("workspaceId", workspaceId)
            .param("endpoint", endpoint)
            .param("key", normalizedKey)
            .param("requestHash", requestHash)
            .param("expiresAt", OffsetDateTime.now().plusHours(24))
            .update();
        if (inserted == 1) {
            return new Claim(true, normalizedKey, null, null, null, "IN_PROGRESS");
        }

        StoredRecord existing = find(workspaceId, endpoint, normalizedKey);
        if (existing == null) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_RACE",
                "幂等请求正在初始化，请稍后使用相同 Idempotency-Key 重试"
            );
        }
        assertSameRequest(existing, requestHash, endpoint);
        return new Claim(
            false,
            normalizedKey,
            existing.resourceId(),
            existing.responseStatus(),
            existing.responseBody(),
            existing.state()
        );
    }

    public void attachResource(String workspaceId, String endpoint, String key, String resourceId) {
        int updated = jdbc.sql("""
                update idempotency_records
                set resource_id = :resourceId
                where workspace_id = :workspaceId and endpoint = :endpoint
                  and idempotency_key = :key and state = 'IN_PROGRESS'
                """)
            .param("resourceId", resourceId)
            .param("workspaceId", workspaceId)
            .param("endpoint", endpoint)
            .param("key", key)
            .update();
        if (updated != 1) {
            throw new IllegalStateException("Unable to attach the idempotent operation resource");
        }
    }

    public void complete(
        String workspaceId,
        String endpoint,
        String key,
        int responseStatus,
        Object responseBody
    ) {
        int updated = jdbc.sql("""
                update idempotency_records
                set state = 'COMPLETED', response_status = :responseStatus,
                    response_body = cast(:responseBody as jsonb), expires_at = :expiresAt
                where workspace_id = :workspaceId and endpoint = :endpoint
                  and idempotency_key = :key
                """)
            .param("responseStatus", responseStatus)
            .param("responseBody", json.write(responseBody))
            .param("expiresAt", OffsetDateTime.now().plusHours(24))
            .param("workspaceId", workspaceId)
            .param("endpoint", endpoint)
            .param("key", key)
            .update();
        if (updated != 1) {
            throw new IllegalStateException("Unable to complete the idempotent operation");
        }
    }

    private StoredRecord find(String workspaceId, String endpoint, String key) {
        return jdbc.sql("""
                select request_hash, state, resource_id, response_status, response_body::text
                from idempotency_records
                where workspace_id = :workspaceId and endpoint = :endpoint
                  and idempotency_key = :key and expires_at > now()
                """)
            .param("workspaceId", workspaceId)
            .param("endpoint", endpoint)
            .param("key", key)
            .query((rs, rowNum) -> new StoredRecord(
                rs.getString("request_hash"),
                rs.getString("state"),
                rs.getString("resource_id"),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body") == null ? null : json.read(rs.getString("response_body"))
            ))
            .optional()
            .orElse(null);
    }

    private void advisoryLock(String workspaceId, String endpoint, String key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:material, 0))")
            .param("material", workspaceId + "\n" + endpoint + "\n" + key)
            .query((rs, rowNum) -> 1)
            .single();
    }

    private void removeExpired(String workspaceId, String endpoint, String key) {
        jdbc.sql("""
                delete from idempotency_records
                where workspace_id = :workspaceId and endpoint = :endpoint
                  and idempotency_key = :key and expires_at <= now()
                """)
            .param("workspaceId", workspaceId)
            .param("endpoint", endpoint)
            .param("key", key)
            .update();
    }

    private static void assertSameRequest(StoredRecord existing, String requestHash, String endpoint) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "相同 Idempotency-Key 对应了不同请求",
                Map.of("endpoint", endpoint)
            );
        }
    }

    private static ApiException inProgress(String endpoint, String resourceId) {
        return new ApiException(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_IN_PROGRESS",
            "相同幂等请求仍在处理中，请稍后重试",
            resourceId == null
                ? Map.of("endpoint", endpoint)
                : Map.of("endpoint", endpoint, "resource_id", resourceId)
        );
    }

    private static String requireKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key 长度必须为 8 到 200 个字符"
            );
        }
        return key;
    }

    public record Outcome(int status, JsonNode body, boolean replayed) {}

    public record Claim(
        boolean claimed,
        String key,
        String resourceId,
        Integer responseStatus,
        JsonNode responseBody,
        String state
    ) {}

    private record StoredRecord(
        String requestHash,
        String state,
        String resourceId,
        Integer responseStatus,
        JsonNode responseBody
    ) {}
}
