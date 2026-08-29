package dev.reasonweave.domainpack;

import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.RequestIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/domain-packs")
@Tag(name = "领域包", description = "领域包清单与不可变版本详情")
public class DomainPackController {
    private final DomainPackService service;

    public DomainPackController(DomainPackService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "列出领域包")
    ApiEnvelope<List<DomainPackModels.DomainPackSummary>> list(HttpServletRequest request) {
        return ApiEnvelope.of(service.list(), RequestIds.current(request));
    }

    @GetMapping("/{key}/versions/{version}")
    @Operation(summary = "读取领域包版本详情")
    ApiEnvelope<DomainPackModels.DomainPackDetail> get(
        @PathVariable String key,
        @PathVariable String version,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.get(key, version), RequestIds.current(request));
    }

    @GetMapping("/{key}/versions/{version}/event-types/{eventType}")
    @Operation(summary = "读取领域包事件定义")
    ApiEnvelope<DomainPackModels.EventTypeView> getEventType(
        @PathVariable String key,
        @PathVariable String version,
        @PathVariable String eventType,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            service.getEventType(key, version, eventType),
            RequestIds.current(request)
        );
    }
}
