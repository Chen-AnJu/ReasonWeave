package dev.reasonweave.explainability;

import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.RequestIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events/{eventId}/graph")
@Tag(name = "因果关系图", description = "按指定调查运行构建的不可变解释图谱")
public class GraphController {
    private final GraphService service;

    public GraphController(GraphService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "读取调查运行的因果关系图")
    ApiEnvelope<GraphModels.GraphView> get(
        @PathVariable String eventId,
        @RequestParam(name = "investigation_id") String investigationId,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.get(eventId, investigationId), RequestIds.current(request));
    }
}
