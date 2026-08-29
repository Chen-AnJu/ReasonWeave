package dev.reasonweave.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.config.ApiOptional;
import java.time.OffsetDateTime;
import java.util.List;

public final class AuditModels {
    private AuditModels() {}

    public record AuditEntry(
        String id,
        JsonNode actor,
        String action,
        JsonNode resource,
        JsonNode beforeState,
        JsonNode afterState,
        @ApiOptional String requestId,
        OffsetDateTime occurredAt
    ) {}

    public record AuditPage(
        List<AuditEntry> items,
        @ApiOptional String nextCursor,
        int limit
    ) {}
}
