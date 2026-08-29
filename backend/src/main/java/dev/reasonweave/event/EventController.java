package dev.reasonweave.event;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.Hashing;
import dev.reasonweave.shared.IdempotencyService;
import dev.reasonweave.shared.JsonSupport;
import dev.reasonweave.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "事件", description = "事件、EventIR 与聚合只读视图")
public class EventController {
    private final EventService service;
    private final IdempotencyService idempotency;
    private final JsonSupport json;

    public EventController(EventService service, IdempotencyService idempotency, JsonSupport json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping
    @Operation(summary = "创建事件并校验 EventIR")
    ResponseEntity<ApiEnvelope<?>> create(
        @Valid @RequestBody EventModels.CreateEventRequest body,
        @RequestHeader("Idempotency-Key") String key,
        HttpServletRequest request
    ) {
        String endpoint = "POST /api/v1/events";
        String requestHash = Hashing.sha256(json.canonicalWrite(body));
        var outcome = idempotency.execute(
            InstanceScope.ID,
            endpoint,
            key,
            requestHash,
            201,
            () -> service.create(body.eventIr(), RequestIds.current(request))
        );
        return ResponseEntity.status(outcome.status())
            .location(URI.create("/api/v1/events/" + outcome.body().path("id").asText()))
            .body(ApiEnvelope.eventIr(outcome.body(), RequestIds.current(request)));
    }

    @GetMapping
    @Operation(summary = "列出事件")
    ApiEnvelope<EventModels.EventPage> list(
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") int limit,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.list(query, status, cursor, limit), RequestIds.current(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取事件详情")
    ApiEnvelope<EventModels.EventDetail> get(@PathVariable String id, HttpServletRequest request) {
        return ApiEnvelope.eventIr(service.get(id), RequestIds.current(request));
    }

    @GetMapping("/{id}/view")
    @Operation(summary = "读取事件聚合视图")
    ApiEnvelope<EventModels.EventView> view(@PathVariable String id, HttpServletRequest request) {
        return ApiEnvelope.of(service.view(id), RequestIds.current(request));
    }
}
