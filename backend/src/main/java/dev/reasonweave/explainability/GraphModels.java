package dev.reasonweave.explainability;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.config.ApiOptional;
import java.util.List;

public final class GraphModels {
    private GraphModels() {}

    public record GraphNode(
        String id,
        String entityId,
        String type,
        String label,
        @ApiOptional String subtitle,
        @ApiOptional String status,
        @ApiOptional Double score,
        @ApiOptional Double coverage,
        JsonNode metadata
    ) {}

    public record GraphEdge(
        String id,
        String source,
        String target,
        String type,
        @ApiOptional Double contribution,
        boolean scoreAffecting,
        @ApiOptional String explanation,
        JsonNode metadata
    ) {}

    public record GraphView(
        String eventId,
        String investigationRunId,
        boolean stale,
        List<String> warnings,
        List<GraphNode> nodes,
        List<GraphEdge> edges
    ) {}
}
