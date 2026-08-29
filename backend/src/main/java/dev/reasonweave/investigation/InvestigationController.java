package dev.reasonweave.investigation;

import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.RequestIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "调查运行", description = "不可变调查运行、知识上下文与差异")
public class InvestigationController {
    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    @PostMapping("/events/{eventId}/investigations")
    @Operation(summary = "发起新调查运行")
    ResponseEntity<ApiEnvelope<InvestigationModels.InvestigationRunView>> start(
        @PathVariable String eventId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest request
    ) {
        var created = service.start(eventId, idempotencyKey, RequestIds.current(request));
        return ResponseEntity.created(URI.create("/api/v1/investigations/" + created.id()))
            .body(ApiEnvelope.of(created, RequestIds.current(request)));
    }

    @GetMapping("/events/{eventId}/investigations")
    @Operation(summary = "列出事件的调查运行")
    ApiEnvelope<InvestigationModels.InvestigationPage> list(
        @PathVariable String eventId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.listForEvent(eventId, cursor, limit), RequestIds.current(request));
    }

    @GetMapping("/investigations/{id}")
    @Operation(summary = "读取调查运行快照")
    ApiEnvelope<InvestigationModels.InvestigationRunView> get(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.get(id), RequestIds.current(request));
    }

    @GetMapping("/investigations/{id}/knowledge-context")
    @Operation(summary = "读取调查知识上下文")
    ApiEnvelope<InvestigationModels.KnowledgeContextView> knowledgeContext(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.knowledgeContext(id), RequestIds.current(request));
    }

    @GetMapping("/investigations/{id}/next-evidence")
    @Operation(summary = "读取下一步取证建议")
    ApiEnvelope<List<InvestigationModels.NextEvidenceView>> nextEvidence(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.nextEvidence(id), RequestIds.current(request));
    }

    @GetMapping("/investigations/{id}/diff")
    @Operation(summary = "比较调查运行快照")
    ApiEnvelope<InvestigationModels.RunDiffView> diff(
        @PathVariable String id,
        @RequestParam(name = "against", required = false) String against,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.diff(id, against), RequestIds.current(request));
    }
}
