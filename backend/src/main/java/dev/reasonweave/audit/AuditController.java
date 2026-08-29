package dev.reasonweave.audit;

import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.RequestIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/events/{eventId}/audit")
@Tag(name = "事件审计", description = "事件级只读审计时间线与结构化导出")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页读取事件审计记录")
    ApiEnvelope<AuditModels.AuditPage> list(
        @PathVariable String eventId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(name = "actor_id", required = false) String actorId,
        @RequestParam(required = false) String action,
        @RequestParam(name = "run_id", required = false) String runId,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            service.list(eventId, cursor, limit, actorId, action, runId),
            RequestIds.current(request)
        );
    }

    @GetMapping("/export")
    @Operation(summary = "导出事件审计 JSONL")
    ResponseEntity<StreamingResponseBody> export(
        @PathVariable String eventId,
        @RequestParam(name = "actor_id", required = false) String actorId,
        @RequestParam(required = false) String action,
        @RequestParam(name = "run_id", required = false) String runId
    ) {
        StreamingResponseBody body = output -> service.writeJsonLines(
            eventId, actorId, action, runId, output
        );
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("reasonweave-audit-" + eventId + ".jsonl", StandardCharsets.UTF_8)
                .build().toString())
            .body(body);
    }
}
