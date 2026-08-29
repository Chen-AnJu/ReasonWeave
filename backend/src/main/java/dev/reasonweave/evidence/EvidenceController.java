package dev.reasonweave.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.RequestIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "证据与观察", description = "现实证据、模型观察与人工复核")
public class EvidenceController {
    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    @PostMapping("/events/{eventId}/evidence/text")
    @Operation(summary = "添加文本证据")
    ResponseEntity<ApiEnvelope<EvidenceModels.EvidenceDetail>> createText(
        @PathVariable String eventId,
        @Valid @RequestBody EvidenceModels.TextEvidenceRequest body,
        HttpServletRequest request
    ) {
        var created = service.createText(eventId, body, RequestIds.current(request));
        return ResponseEntity.created(URI.create("/api/v1/evidence/" + created.evidence().id()))
            .body(ApiEnvelope.of(created, RequestIds.current(request)));
    }

    @PostMapping("/events/{eventId}/evidence")
    @Operation(summary = "上传证据文件")
    ResponseEntity<ApiEnvelope<EvidenceModels.EvidenceDetail>> upload(
        @PathVariable String eventId,
        @RequestPart("file") MultipartFile file,
        HttpServletRequest request
    ) {
        var created = service.upload(eventId, file, RequestIds.current(request));
        return ResponseEntity.created(URI.create("/api/v1/evidence/" + created.evidence().id()))
            .body(ApiEnvelope.of(created, RequestIds.current(request)));
    }

    @PostMapping("/events/{eventId}/evidence/bundles")
    @Operation(summary = "导入标准 Observation Bundle")
    ResponseEntity<ApiEnvelope<EvidenceModels.ObservationBundleImportView>> importBundle(
        @PathVariable String eventId,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(schema = @Schema(implementation = EvidenceModels.ObservationBundleRequest.class))
        )
        @RequestBody JsonNode body,
        HttpServletRequest request
    ) {
        var imported = service.importObservationBundle(
            eventId, body, RequestIds.current(request)
        );
        return ResponseEntity.status(imported.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(ApiEnvelope.of(imported, RequestIds.current(request)));
    }

    @GetMapping("/evidence")
    @Operation(summary = "列出证据")
    ApiEnvelope<EvidenceModels.EvidencePage> list(
        @RequestParam(name = "event_id", required = false) String eventId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") int limit,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.list(eventId, cursor, limit), RequestIds.current(request));
    }

    @GetMapping("/evidence/{id}")
    @Operation(summary = "读取证据详情")
    ApiEnvelope<EvidenceModels.EvidenceDetail> get(@PathVariable String id, HttpServletRequest request) {
        return ApiEnvelope.of(service.get(id), RequestIds.current(request));
    }

    @PatchMapping("/observations/{id}")
    @Operation(summary = "人工复核观察")
    ResponseEntity<ApiEnvelope<EvidenceModels.ObservationView>> verify(
        @PathVariable String id,
        @Valid @RequestBody EvidenceModels.ObservationVerificationRequest body,
        @RequestHeader("If-Match") String ifMatch,
        HttpServletRequest request
    ) {
        long version = parseVersion(ifMatch);
        var updated = service.verifyObservation(id, body, version, RequestIds.current(request));
        return ResponseEntity.ok()
            .eTag(Long.toString(updated.version()))
            .body(ApiEnvelope.of(updated, RequestIds.current(request)));
    }

    @PostMapping("/evidence/{id}/reprocess")
    @Operation(summary = "重新处理证据")
    ApiEnvelope<EvidenceModels.EvidenceDetail> reprocess(@PathVariable String id, HttpServletRequest request) {
        return ApiEnvelope.of(service.reprocess(id, RequestIds.current(request)), RequestIds.current(request));
    }

    private static long parseVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_VALUE",
                "If-Match 请求头格式无效"
            );
        }
        try {
            return Long.parseLong(value.replace("\"", "").trim());
        }
        catch (NumberFormatException exception) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_VALUE",
                "If-Match 请求头格式无效",
                Map.of("name", "If-Match", "value", value)
            );
        }
    }
}
